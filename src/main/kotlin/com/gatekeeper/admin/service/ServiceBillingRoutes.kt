package com.gatekeeper.admin.service


import com.gatekeeper.admin.application.serviceCoordinates
import com.gatekeeper.admin.model.*
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.math.BigDecimal
import java.util.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore

fun Route.registerServiceBillingRoutes(dependencies: ServiceAdminDependencies) {
    val projects = dependencies.projects
    val auditRepository = dependencies.auditRepository
    val scribed = dependencies.scribed
    val serviceRepository = dependencies.serviceRepository
    val serviceInvoices = dependencies.serviceInvoices

    post("/api/admin/projects/{projectId}/services/{serviceId}/invoice") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@post
        val project = projects.findActiveById(projectId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val body = runCatching { call.receive<CreateServiceInvoiceRequest>() }.getOrNull()
            ?: return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid service invoice request"
            )
        val description = body.description.trim()
        if (description.isBlank() || description.length > 500) {
            return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_invoice_description",
                "Invoice description is required and must be at most 500 characters"
            )
        }
        val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_invoice_amount",
                "Enter a service invoice amount greater than zero"
            )
        }
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        try {
            serviceInvoices.create(serviceId, projectId, description, amount, project.currency, project.dueDate)
        } catch (e: IllegalArgumentException) {
            return@post call.respondError(
                HttpStatusCode.Conflict,
                "service_invoice_already_exists",
                e.message ?: "An invoice already exists for this service"
            )
        }
        auditRepository.write(
            projectId,
            "service_invoice_created",
            actor,
            "service=${service.name} amount=$amount ${project.currency}"
        )
        val invoiceView = serviceInvoices.view(serviceId)!!
        call.respond(HttpStatusCode.Created, ServiceInvoiceResponse("created", serviceId.toString(), invoiceJson(invoiceView, serviceInvoices.balance(serviceId) ?: amount)))
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/invoice") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (projects.findActiveById(projectId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val invoice = serviceInvoices.view(serviceId)
        if (invoice == null) {
            return@get call.respondError(
                HttpStatusCode.NotFound,
                "invoice_unavailable",
                "No invoice has been created for this service"
            )
        }
        call.respond(buildJsonObject {
            put("invoice", invoiceJson(invoice, serviceInvoices.balance(serviceId) ?: BigDecimal.ZERO))
        })
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/invoice/download") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val project = projects.findActiveById(projectId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
        if (service == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val invoice = serviceInvoices.find(serviceId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "invoice_unavailable", "No invoice has been created for this service")
        val serviceAdjustments = call.application.get<ProjectBalanceAdapter>().serviceAdjustments(serviceId)
        val billedAmount = serviceAdjustments.fold(invoice.amount) { total, adjustment ->
            total + if (adjustment.type == AdjustmentType.ADDITIONAL_CHARGE) adjustment.amount else -adjustment.amount
        }
        val invoiceView = serviceInvoices.view(serviceId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "invoice_unavailable", "No invoice has been created for this service")
        val balance = serviceInvoices.balance(serviceId) ?: BigDecimal.ZERO
        val adjustmentItems = listOf(
            ScribedIntegrationClient.InvoiceItem(service.name, amount = invoice.amount.toPlainString())
        ) + serviceAdjustments.map { adjustment ->
            ScribedIntegrationClient.InvoiceItem(
                name = if (adjustment.type == AdjustmentType.ADDITIONAL_CHARGE) "Additional charge" else "Discount",
                amount = (if (adjustment.type == AdjustmentType.ADDITIONAL_CHARGE) adjustment.amount else adjustment.amount.negate()).toPlainString()
            )
        }
        val (status, bytes) = scribed.generateInvoice(
            project, "${project.name} — ${service.name}", invoice.description,
            billedAmount.max(BigDecimal.ZERO).toPlainString(), invoice.currency,
            invoice.dueDate, serviceId.toString(), invoice.createdAt.toLocalDate(),
            invoiceView.paid.toPlainString(), balance.toPlainString(), adjustmentItems,
            when {
                balance.signum() == 0 -> "paid"
                invoiceView.paid.signum() > 0 -> "partially_paid"
                else -> "unpaid"
            }
        )
        if (bytes == null) {
            return@get call.respondError(
                status,
                "invoice_download_failed",
                "Scribed could not generate the service invoice"
            )
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=invoice-${project.slug}-service-$serviceId.pdf"
        )
        call.respondBytes(bytes, ContentType.Application.Pdf)
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/payments/{paymentId}/receipt") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val project = projects.findActiveById(projectId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
        if (service == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val paymentId = runCatching { UUID.fromString(call.parameters["paymentId"]) }.getOrNull()
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_payment_id", "Invalid payment ID")
        val payment =
            call.application.get<GetPayment>()(paymentId)?.takeIf { it.projectId == projectId && it.serviceId == serviceId }
                ?: return@get call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
        if (payment.gatewayStatus != "success") {
            return@get call.respondError(
                HttpStatusCode.Conflict,
                "payment_not_complete",
                "A receipt is available after successful payment"
            )
        }
        val (status, bytes) = scribed.receiptPdfForServicePayment(
            project,
            service.name,
            payment,
            call.application.get<PaymentDomainRepository>(),
            call.application.get<ProjectBalanceAdapter>()
        )
        if (bytes == null) {
            return@get call.respondError(
                if (status == HttpStatusCode.NotFound) status else HttpStatusCode.BadGateway,
                "receipt_download_failed",
                "Scribed could not generate or retrieve this receipt"
            )
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            "inline; filename=receipt-${project.slug}-${payment.providerReference}.pdf"
        )
        call.respondBytes(bytes, ContentType.Application.Pdf)
    }

    post("/api/admin/projects/{projectId}/services/{serviceId}/adjustments") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@post
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val body = runCatching { call.receive<CreateServiceAdjustmentRequest>() }.getOrNull()
            ?: return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid service adjustment request"
            )
        val type = runCatching { AdjustmentType.valueOf(body.type.trim().uppercase()) }.getOrNull()
            ?: return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_adjustment_type",
                "type must be ADDITIONAL_CHARGE or DISCOUNT"
            )
        val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) return@post call.respondError(
            HttpStatusCode.BadRequest,
            "invalid_adjustment_amount",
            "amount must be greater than zero"
        )
        if (body.reason.isBlank()) return@post call.respondError(
            HttpStatusCode.BadRequest,
            "invalid_adjustment_reason",
            "reason is required"
        )
        if (body.reason.length > 500) return@post call.respondError(
            HttpStatusCode.BadRequest,
            "invalid_adjustment_reason",
            "reason must be at most 500 characters"
        )
        val invoice = serviceInvoices.view(serviceId)
        if (invoice == null) {
            return@post call.respondError(
                HttpStatusCode.Conflict,
                "service_invoice_required",
                "Create a service invoice before adding service charges"
            )
        }
        if (type == AdjustmentType.DISCOUNT) {
            val remaining = serviceInvoices.balance(serviceId) ?: BigDecimal.ZERO
            if (amount > remaining) return@post call.respondError(
                HttpStatusCode.BadRequest,
                "discount_exceeds_balance",
                "A discount cannot exceed this service's outstanding balance"
            )
        }
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        val adjustment = try {
            call.application.get<ProjectAdjustmentStore>()
                .create(projectId, type, amount, body.reason, actor, serviceId)
        } catch (e: IllegalArgumentException) {
            return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_adjustment",
                e.message ?: "Invalid adjustment"
            )
        }
        auditRepository.write(
            projectId,
            "service_adjustment",
            actor,
            "service=${service.name} ${type.name} amount=$amount reason=${body.reason.trim()}"
        )
        call.respond(
            HttpStatusCode.Created,
            ServiceAdjustmentView(
                adjustment.id.toString(),
                projectId.toString(),
                serviceId.toString(),
                type.name,
                amount.toDouble(),
                adjustment.reason,
                actor,
                adjustment.createdAt.toString()
            )
        )
    }
}

private fun invoiceJson(invoice: ServiceInvoiceApplicationService.InvoiceView, balance: BigDecimal) = buildJsonObject {
    put("id", invoice.scribedInvoiceId ?: "gatekeeper-${invoice.serviceId}")
    put("description", invoice.description)
    put("amount", invoice.amount.toPlainString())
    put("paid", invoice.paid.toPlainString())
    put("balance", balance.toPlainString())
    put("currency", invoice.currency)
    put("due_date", invoice.dueDate?.toString())
    put("payments", buildJsonArray {
        invoice.payments.forEach { payment ->
            addJsonObject {
                put("id", payment.id.toString())
                put("provider_reference", payment.providerReference)
                put("amount", payment.amount.toPlainString())
            }
        }
    })
}
