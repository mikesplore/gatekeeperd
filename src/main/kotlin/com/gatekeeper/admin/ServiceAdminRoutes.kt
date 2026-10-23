package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.nginx.requireValidHostname
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.math.BigDecimal
import org.koin.ktor.ext.get

private val supportedServiceTypes = setOf("frontend", "backend", "worker", "database", "redis", "cache", "custom")

@Serializable
data class ServiceAdminView(
    val id: String, val projectId: String, val name: String, val serviceType: String, val accessStatus: String,
    val isDefault: Boolean,
    val blockReason: String?, val blockReasonCode: String? = null, val blockReasonNote: String? = null
)

@Serializable
data class CreateServiceRequest(val name: String, val serviceType: String = "frontend")

@Serializable
data class CreateServiceAdjustmentRequest(val type: String, val amount: Double, val reason: String)

@Serializable
data class CreateServiceInvoiceRequest(val description: String, val amount: Double)

@Serializable
data class ServiceInvoiceResponse(val status: String, val serviceId: String, val invoice: JsonObject)

@Serializable
data class ServiceAdjustmentView(val id: String, val projectId: String, val serviceId: String, val type: String, val amount: Double, val reason: String, val actor: String, val createdAt: String)

@Serializable
data class UpdateServiceRequest(
    val name: String? = null, val accessStatus: String? = null, val blockReason: String? = null,
    val blockReasonCode: String? = null, val blockReasonNote: String? = null,
    val isDefault: Boolean? = null, val serviceType: String? = null
)

@Serializable
data class ServiceDomainBindingRequest(val domain: String, val containerPort: Int? = null)

@Serializable
data class ServiceDomainBindingView(
    val id: String, val serviceId: String, val domain: String,
    val containerPort: Int?, val status: String
)

@Serializable
data class ServiceDomainBindingsResponse(
    val serviceId: String,
    val publishedPorts: List<Int>,
    val bindings: List<ServiceDomainBindingView>,
    val unboundPorts: List<Int>
)

@Serializable
data class EnvironmentVersionView(
    val id: String, val version: Int, val environment: String, val keys: List<String>, val createdAt: String, val createdBy: String?
)

@Serializable
data class SharedEnvironmentMetadataView(
    val projectId: String, val environment: String, val latest: EnvironmentVersionView?,
    val versions: List<EnvironmentVersionView>, val values: String = "write-only"
)

@Serializable
data class ServiceEnvironmentMetadataView(
    val projectId: String, val serviceId: String, val environment: String,
    val effectiveValues: Map<String, String>
)

@Serializable
data class EnvironmentWriteRequest(
    val environment: String = "production",
    val values: Map<String, String>,
    val replaceExisting: Boolean = false,
    val sharedEnvironmentSetId: String? = null,
    val sharedEnvironmentSetVersion: Int? = null
)

@Serializable
data class EnvironmentWriteResponse(
    val deploymentIds: List<String>
)

@Serializable
data class FingerprintedEnvironmentVariableView(val key: String, val source: String, val fingerprint: String)

@Serializable
data class ActiveDeploymentInspectionView(
    val deploymentId: String, val status: String, val containerName: String?, val serviceId: String, val environment: String,
    val publishedPorts: Map<String, Int> = emptyMap(),
    val imageName: String, val imageTag: String, val imageDigest: String?, val commitSha: String?, val activeAt: String?,
    val sharedSetId: String?, val sharedSetVersion: Int?, val serviceSetId: String?, val serviceSetVersion: Int?,
    val variables: List<FingerprintedEnvironmentVariableView>,
    val publicExposure: Boolean = false,
    val fingerprintAlgorithm: String = "HMAC-SHA-256"
)

fun Application.configureServiceAdminRoutes() {
    val projects = get<ProjectRepository>()
    val auditRepository = get<AuditRepositoryPort>()
    val environmentSets = get<EnvironmentSetStore>()
    val scribed = get<ScribedIntegrationClient>()
    val deploymentJobs = get<DeploymentJobRepository>()
    val serviceDomains = get<ServiceDomainRepository>()
    val serviceRepository = get<ServiceRepository>()
    val siteRepository = get<SiteRepository>()
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/projects/{projectId}/services") {
                val projectId = call.serviceProjectId(projects) ?: return@get
                if (projects.findActiveById(projectId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                call.respond(serviceRepository.listByProjectId(projectId).map { it.toView() })
            }

            post("/api/admin/projects/{projectId}/services") {
                val projectId = call.serviceProjectId(projects) ?: return@post
                if (projects.findActiveById(projectId) == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                val body = runCatching { call.receive<CreateServiceRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A service name is required")
                val name = body.name.trim()
                val serviceType = body.serviceType.trim().lowercase()
                if (serviceType !in supportedServiceTypes) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_type", "Unsupported service type")
                }
                if (!validServiceName(name) || name.equals("default", ignoreCase = true)) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_name", "Service name is invalid or reserved")
                }
                val service = runCatching { serviceRepository.create(projectId, name, serviceType) }.getOrElse {
                    return@post call.respondError(HttpStatusCode.Conflict, "service_create_conflict", "A service with that name already exists")
                }
                call.respond(HttpStatusCode.Created, service.toView())
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
                val service = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                call.respond(service.toView())
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/domains") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
                val service = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val runtimePorts = DeploymentApplicationService.activeRuntime(service.id, "production")?.ports?.keys.orEmpty()
                call.respond(ServiceDomainBindingsResponse(
                    serviceId = service.id.toString(),
                    publishedPorts = runtimePorts.sorted(),
                    bindings = serviceDomains.reconcilePorts(service.id, runtimePorts).map { it.toView() },
                    unboundPorts = serviceDomains.unboundPorts(service.id, runtimePorts)
                ))
            }

            post("/api/admin/projects/{projectId}/services/{serviceId}/domains") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@post
                val service = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val body = runCatching { call.receive<ServiceDomainBindingRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain binding request")
                val domain = runCatching { requireValidHostname(body.domain) }.getOrElse {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
                }
                val ports = DeploymentApplicationService.activeRuntime(service.id, "production")?.ports?.keys.orEmpty()
                if (ports.size > 1 && body.containerPort == null) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "container_port_required", "Select a container port")
                }
                if (body.containerPort != null && body.containerPort !in 1..65535) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_container_port", "containerPort must be between 1 and 65535")
                }
                if (body.containerPort != null && body.containerPort !in ports) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port is not published")
                }
                if (serviceDomains.findByDomain(domain) != null) {
                    return@post call.respondError(HttpStatusCode.Conflict, "domain_in_use", "Domain is already bound")
                }
                val binding = serviceDomains.add(service.id, domain, body.containerPort ?: ports.singleOrNull())
                call.respond(HttpStatusCode.Created, binding.toView())
            }

            put("/api/admin/projects/{projectId}/services/{serviceId}/domains/{bindingId}") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@put
                val service = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val bindingId = runCatching { UUID.fromString(call.parameters["bindingId"]) }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_binding_id", "Binding ID is invalid")
                val body = runCatching { call.receive<ServiceDomainBindingRequest>() }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain binding request")
                val domain = runCatching { requireValidHostname(body.domain) }.getOrElse {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
                }
                val existing = serviceDomains.findByDomain(domain)
                if (existing != null && existing.id != bindingId) {
                    return@put call.respondError(HttpStatusCode.Conflict, "domain_in_use", "Domain is already bound")
                }
                val ports = DeploymentApplicationService.activeRuntime(service.id, "production")?.ports?.keys.orEmpty()
                if (body.containerPort != null && body.containerPort !in ports) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port is not published")
                }
                val binding = serviceDomains.update(bindingId, service.id, domain, body.containerPort ?: ports.singleOrNull())
                    ?: return@put call.respondError(HttpStatusCode.NotFound, "domain_not_bound", "Domain binding was not found")
                call.respond(binding.toView())
            }

            delete("/api/admin/projects/{projectId}/services/{serviceId}/domains/{bindingId}") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@delete
                val service = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@delete call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val bindingId = runCatching { UUID.fromString(call.parameters["bindingId"]) }.getOrNull()
                    ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_binding_id", "Binding ID is invalid")
                if (!serviceDomains.removeById(service.id, bindingId)) {
                    return@delete call.respondError(HttpStatusCode.NotFound, "domain_not_bound", "Domain binding was not found")
                }
                call.respond(mapOf("status" to "domain_removed", "bindingId" to bindingId.toString()))
            }

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

            patch("/api/admin/projects/{projectId}/services/{serviceId}") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@patch
                val current = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@patch call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val body = runCatching { call.receive<UpdateServiceRequest>() }.getOrNull()
                    ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service update")
                if (body.isDefault == false) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_default_service", "Choose another service as default; the current default cannot be unset directly")
                }
                val name = body.name?.trim()
                if (name != null && (!validServiceName(name) || (!current.isDefault && name.equals("default", ignoreCase = true)))) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_service_name", "Service name is invalid or reserved")
                }
                if (body.accessStatus != null && body.accessStatus !in setOf("active", "blocked", "manual_block")) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_access_status", "accessStatus must be active, blocked, or manual_block")
                }
                if ((body.blockReason?.length ?: 0) > 500) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason", "blockReason must be at most 500 characters")
                }
                if ((body.blockReasonNote?.length ?: 0) > 500) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason_note", "blockReasonNote must be at most 500 characters")
                }
                val blockReasonCode = body.blockReasonCode?.let { code ->
                    AccessBlockReason.entries.firstOrNull { it.value == code }
                        ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason_code", "Unsupported blockReasonCode")
                }
                val serviceType = body.serviceType?.trim()?.lowercase()
                if (serviceType != null && serviceType !in supportedServiceTypes) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_service_type", "Unsupported service type")
                }
                val updated = runCatching {
                    serviceRepository.update(projectId, serviceId, name, body.accessStatus, body.blockReason, blockReasonCode, body.blockReasonNote, body.isDefault == true, serviceType)
                }.getOrElse {
                    return@patch call.respondError(HttpStatusCode.Conflict, "service_update_conflict", "The service update conflicts with an existing service")
                } ?: return@patch call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                call.respond(updated.toView())
            }

            delete("/api/admin/projects/{projectId}/services/{serviceId}") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@delete
                val current = serviceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@delete call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                if (current.isDefault) {
                    return@delete call.respondError(HttpStatusCode.Conflict, "default_service_required", "The default service cannot be deleted")
                }
                if (!serviceRepository.delete(projectId, serviceId)) {
                    return@delete call.respondError(HttpStatusCode.Conflict, "service_in_use", "Services with deployment, environment, or site history cannot be deleted")
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/admin/projects/{projectId}/environment/shared") {
                val projectId = call.serviceProjectId(projects) ?: return@get
                if (projects.findActiveById(projectId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val metadata = environmentSets.sharedMetadata(projectId, environment)
                call.respond(SharedEnvironmentMetadataView(
                    projectId.toString(), environment, metadata.latest?.toView(), metadata.versions.map { it.toView() }
                ))
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
                if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val metadata = environmentSets.serviceMetadata(projectId, serviceId, environment)!!
                call.respond(ServiceEnvironmentMetadataView(
                    projectId.toString(), serviceId.toString(), environment, metadata.effectiveValues
                ))
            }

            put("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@put
                if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val body = runCatching { call.receive<EnvironmentWriteRequest>() }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service environment request")
                val environment = normalizeEnvironment(body.environment)
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                if (!validEnvironmentValues(body.values)) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment_values", "Environment variable names or values are invalid")
                }
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "admin"
                val result = runCatching {
                    val sharedId = body.sharedEnvironmentSetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (body.sharedEnvironmentSetId != null && sharedId == null) {
                        return@put call.respondError(HttpStatusCode.BadRequest, "invalid_shared_environment_reference", "Shared environment ID is invalid")
                    }
                    deploymentJobs.updateServiceEnvironmentAndDeploy(
                        projectId, serviceId, environment, body.values, actor,
                        replaceExisting = body.replaceExisting,
                        sharedEnvironmentSetId = sharedId,
                        sharedEnvironmentSetVersion = body.sharedEnvironmentSetVersion
                    )
                }.getOrElse { error ->
                    return@put call.respondError(HttpStatusCode.Conflict, "service_environment_update_failed", error.message ?: "Service environment could not be saved")
                } ?: return@put call.respondError(HttpStatusCode.Conflict, "source_runtime_required", "Save this service's source/runtime configuration before editing its environment")
                call.respond(HttpStatusCode.Accepted, EnvironmentWriteResponse(
                    result.deploymentIds.map(UUID::toString)
                ))
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/active-deployment") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
                if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val active = environmentSets.activeDeployment(serviceId, environment)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "active_deployment_not_found", "No active deployment exists for this service and environment")
                call.respond(active.toView())
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/runtime") {
                val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
                if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val runtime = environmentSets.runtimeDeployment(serviceId, environment)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "runtime_not_found", "No active or ready deployment runtime exists for this service and environment")
                call.respond(runtime.toView())
            }
        }
    }
}

private suspend fun ApplicationCall.serviceProjectId(projects: ProjectRepository): UUID? {
    val raw = parameters["projectId"]
    if (raw == null) {
        respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is required")
        return null
    }
    val project = runCatching { UUID.fromString(raw) }
        .getOrNull()
        ?.let(projects::findActiveById)
        ?: projects.findBySlug(raw)
    if (project == null) {
        respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        return null
    }
    return project.id
}

private suspend fun ApplicationCall.serviceCoordinates(projects: ProjectRepository): Pair<UUID, UUID>? {
    val projectId = serviceProjectId(projects) ?: return null
    val serviceId = parameters["serviceId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: run {
            respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
            return null
        }
    return projectId to serviceId
}

private fun validServiceName(value: String) = value.length in 1..63 &&
    value.first().isLetterOrDigit() &&
    value.all { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.' } &&
    value.last().isLetterOrDigit()
private fun normalizeEnvironment(value: String) = value.trim().lowercase().takeIf { it.matches(Regex("^[a-z][a-z0-9_-]{0,31}$")) }
private fun validEnvironmentValues(values: Map<String, String>) = values.all { (key, value) ->
    key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !key.contains('=') && !value.contains('\u0000')
}

private fun ServiceRepository.ServiceRecord.toView() = ServiceAdminView(
    id.toString(), projectId.toString(), name, serviceType, accessStatus, isDefault, blockReason,
    blockReasonCode?.value, blockReasonNote
)

private fun ServiceDomainRepository.Binding.toView() = ServiceDomainBindingView(
    id = id.toString(),
    serviceId = serviceId.toString(),
    domain = domain,
    containerPort = containerPort,
    status = status
)
private fun EnvironmentVersionMetadata.toView() = EnvironmentVersionView(id.toString(), version, environment, keys, createdAt.toString(), createdBy)
private fun ActiveDeploymentInspection.toView() = ActiveDeploymentInspectionView(
    deploymentId.toString(), status, containerName, serviceId.toString(), environment, publishedPorts.mapKeys { it.key.toString() }, imageName, imageTag, imageDigest, commitSha,
    activeAt?.toString(), sharedSetId?.toString(), sharedSetVersion, serviceSetId?.toString(), serviceSetVersion,
    variables.map { FingerprintedEnvironmentVariableView(it.key, it.source, it.fingerprint) }, publicExposure
)
