package com.gatekeeper.admin.service


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.respondError
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentVersionMetadata
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.deployment.domain.repository.ActiveDeploymentInspection
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.service.domain.repository.ServiceRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.*
import java.util.UUID
import java.math.BigDecimal
import org.koin.ktor.ext.get

fun Route.registerServiceBillingRoutes(dependencies: ServiceAdminDependencies) {
    val projects = dependencies.projects
    val auditRepository = dependencies.auditRepository
    val environmentSets = dependencies.environmentSets
    val scribed = dependencies.scribed
    val deploymentJobs = dependencies.deploymentJobs
    val serviceDomains = dependencies.serviceDomains
    val serviceRepository = dependencies.serviceRepository
    val siteRepository = dependencies.siteRepository
    val nginxSiteStorage = dependencies.nginxSiteStorage
    val docker = dependencies.docker
    val validator = dependencies.validator
    post("/api/admin/projects/{projectId}/services/{serviceId}/invoice") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@post
        val project = projects.findActiveById(projectId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val body = runCatching { call.receive<CreateServiceInvoiceRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service invoice request")
        val description = body.description.trim()
        if (description.isBlank() || description.length > 500) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_invoice_description", "Invoice description is required and must be at most 500 characters")
        }
        val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_invoice_amount", "Enter a service invoice amount greater than zero")
        }
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        val result = scribed.createServiceInvoice(project, serviceId.toString(), service.name, description, amount.toPlainString())
        if (result.status == HttpStatusCode.Conflict) {
            return@post call.respondError(HttpStatusCode.Conflict, "service_invoice_already_exists", "Scribed already has an invoice for this service")
        }
        if (result.status == null || !result.status.isSuccess()) {
            return@post call.respondError(HttpStatusCode.BadGateway, "service_invoice_creation_failed", "Scribed could not create the service invoice")
        }
        val confirmed = scribed.serviceInvoiceStatus(serviceId.toString())
        if (confirmed.body == null) {
            return@post call.respondError(HttpStatusCode.BadGateway, "service_invoice_creation_failed", "The created service invoice could not be confirmed")
        }
        if (result.created == false) {
            return@post call.respond(HttpStatusCode.OK, ServiceInvoiceResponse("already_exists", serviceId.toString(), confirmed.body))
        }
        auditRepository.write(projectId, "service_invoice_created", actor, "service=${service.name} amount=$amount ${project.currency}")
        call.respond(HttpStatusCode.Created, ServiceInvoiceResponse("created", serviceId.toString(), confirmed.body))
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/invoice") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (projects.findActiveById(projectId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val lookup = scribed.serviceInvoiceStatus(serviceId.toString())
        if (lookup.status == HttpStatusCode.NotFound) {
            return@get call.respondError(HttpStatusCode.NotFound, "invoice_unavailable", "No invoice has been created for this service")
        }
        if (lookup.body == null || lookup.status == null || !lookup.status.isSuccess()) {
            return@get call.respondError(HttpStatusCode.BadGateway, "invoice_unavailable", "The service invoice could not be loaded")
        }
        call.respond(lookup.body)
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/invoice/download") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val project = projects.findActiveById(projectId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val (status, bytes) = scribed.serviceInvoicePdf(
            project, serviceId, call.application.get<PaymentDomainRepository>(), call.application.get<ProjectBalanceAdapter>()
        )
        if (bytes == null) {
            return@get call.respondError(HttpStatusCode.BadGateway, "invoice_download_failed", "Scribed could not synchronize or retrieve the service invoice")
        }
        call.response.header(io.ktor.http.HttpHeaders.ContentDisposition, "attachment; filename=invoice-${project.slug}-service-$serviceId.pdf")
        call.respondBytes(bytes, io.ktor.http.ContentType.Application.Pdf)
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/payments/{paymentId}/receipt") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val project = projects.findActiveById(projectId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val paymentId = runCatching { UUID.fromString(call.parameters["paymentId"]) }.getOrNull()
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_payment_id", "Invalid payment ID")
        val payment = call.application.get<GetPayment>()(paymentId)?.takeIf { it.projectId == projectId && it.serviceId == serviceId }
            ?: return@get call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
        if (payment.gatewayStatus != "success") {
            return@get call.respondError(HttpStatusCode.Conflict, "payment_not_complete", "A receipt is available after successful payment")
        }
        val (status, bytes) = scribed.receiptPdfForServicePayment(
            project, serviceId, payment, call.application.get<PaymentDomainRepository>(), call.application.get<ProjectBalanceAdapter>()
        )
        if (bytes == null) {
            return@get call.respondError(if (status == HttpStatusCode.NotFound) status else HttpStatusCode.BadGateway, "receipt_download_failed", "Scribed could not generate or retrieve this receipt")
        }
        call.response.header(io.ktor.http.HttpHeaders.ContentDisposition, "inline; filename=receipt-${project.slug}-${payment.providerReference}.pdf")
        call.respondBytes(bytes, io.ktor.http.ContentType.Application.Pdf)
    }

    post("/api/admin/projects/{projectId}/services/{serviceId}/adjustments") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@post
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val body = runCatching { call.receive<CreateServiceAdjustmentRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service adjustment request")
        val type = runCatching { AdjustmentType.valueOf(body.type.trim().uppercase()) }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_type", "type must be ADDITIONAL_CHARGE or DISCOUNT")
        val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_amount", "amount must be greater than zero")
        if (body.reason.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_reason", "reason is required")
        if (body.reason.length > 500) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_reason", "reason must be at most 500 characters")
        val invoice = scribed.serviceInvoiceStatus(serviceId.toString())
        if (invoice.status == HttpStatusCode.NotFound) {
            return@post call.respondError(HttpStatusCode.Conflict, "service_invoice_required", "Create a service invoice in Scribed before adding service charges")
        }
        if (invoice.body == null) return@post call.respondError(HttpStatusCode.BadGateway, "invoice_integration_unavailable", "The service invoice could not be checked")
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        val adjustment = try { call.application.get<ProjectAdjustmentStore>().create(projectId, type, amount, body.reason, actor, serviceId) }
            catch (e: IllegalArgumentException) { return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment", e.message ?: "Invalid adjustment") }
        auditRepository.write(projectId, "service_adjustment", actor, "service=${service.name} ${type.name} amount=$amount reason=${body.reason.trim()}")
        scribed.notifyServiceLedger(serviceId, "adjustment-${adjustment.id}", call.application.get<ProjectBalanceAdapter>(), call.application.get())
        call.respond(HttpStatusCode.Created, ServiceAdjustmentView(adjustment.id.toString(), projectId.toString(), serviceId.toString(), type.name, amount.toDouble(), adjustment.reason, actor, adjustment.createdAt.toString()))
    }
}
