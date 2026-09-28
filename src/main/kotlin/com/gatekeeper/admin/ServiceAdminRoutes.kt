package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.EnvironmentSetRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ServiceRepository
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID
import java.math.BigDecimal

@Serializable
data class ServiceAdminView(
    val id: String, val projectId: String, val name: String, val accessStatus: String,
    val blockReason: String?, val blockReasonCode: String? = null, val blockReasonNote: String? = null
)

@Serializable
data class CreateServiceRequest(val name: String)

@Serializable
data class CreateServiceAdjustmentRequest(val type: String, val amount: Double, val reason: String)

@Serializable
data class ServiceAdjustmentView(val id: String, val projectId: String, val serviceId: String, val type: String, val amount: Double, val reason: String, val actor: String, val createdAt: String)

@Serializable
data class UpdateServiceRequest(
    val name: String? = null, val accessStatus: String? = null, val blockReason: String? = null,
    val blockReasonCode: String? = null, val blockReasonNote: String? = null
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
    val configuredSetId: String?, val configuredSetVersion: Int?,
    val configuredSharedSetId: String?, val configuredSharedSetVersion: Int?,
    val versions: List<EnvironmentVersionView>, val values: String = "write-only"
)

@Serializable
data class EnvironmentWriteRequest(
    val environment: String = "production",
    val values: Map<String, String>,
    val sharedEnvironmentSetId: String? = null,
    val sharedEnvironmentSetVersion: Int? = null
)

@Serializable
data class EnvironmentWriteResponse(
    val setId: String, val version: Int, val deploymentIds: List<String>, val values: String = "write-only"
)

@Serializable
data class FingerprintedEnvironmentVariableView(val key: String, val source: String, val fingerprint: String)

@Serializable
data class ActiveDeploymentInspectionView(
    val deploymentId: String, val containerName: String?, val serviceId: String, val environment: String,
    val imageName: String, val imageTag: String, val imageDigest: String?, val commitSha: String?, val activeAt: String?,
    val sharedSetId: String?, val sharedSetVersion: Int?, val serviceSetId: String?, val serviceSetVersion: Int?,
    val variables: List<FingerprintedEnvironmentVariableView>,
    val fingerprintAlgorithm: String = "HMAC-SHA-256"
)

fun Application.configureServiceAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/projects/{projectId}/services") {
                val projectId = call.serviceProjectId() ?: return@get
                if (ProjectRepository.findActiveById(projectId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                call.respond(ServiceRepository.listByProjectId(projectId).map { it.toView() })
            }

            post("/api/admin/projects/{projectId}/services") {
                val projectId = call.serviceProjectId() ?: return@post
                if (ProjectRepository.findActiveById(projectId) == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                val body = runCatching { call.receive<CreateServiceRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A service name is required")
                val name = body.name.trim()
                if (!validServiceName(name) || name == "default") {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_name", "Service name is invalid or reserved")
                }
                val service = runCatching { ServiceRepository.create(projectId, name) }.getOrElse {
                    return@post call.respondError(HttpStatusCode.Conflict, "service_create_conflict", "A service with that name already exists")
                }
                call.respond(HttpStatusCode.Created, service.toView())
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@get
                val service = ServiceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                call.respond(service.toView())
            }

            post("/api/admin/projects/{projectId}/services/{serviceId}/adjustments") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@post
                val service = ServiceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val body = runCatching { call.receive<CreateServiceAdjustmentRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service adjustment request")
                val type = runCatching { AdjustmentType.valueOf(body.type.trim().uppercase()) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_type", "type must be ADDITIONAL_CHARGE or DISCOUNT")
                val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
                if (amount == null || amount <= BigDecimal.ZERO) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_amount", "amount must be greater than zero")
                if (body.reason.isBlank()) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_reason", "reason is required")
                if (body.reason.length > 500) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_reason", "reason must be at most 500 characters")
                val invoice = ScribedIntegrationClient.serviceInvoiceStatus(serviceId.toString())
                if (invoice.status == HttpStatusCode.NotFound) {
                    return@post call.respondError(HttpStatusCode.Conflict, "service_invoice_required", "Create a service invoice in Scribed before adding service charges")
                }
                if (invoice.body == null) return@post call.respondError(HttpStatusCode.BadGateway, "invoice_integration_unavailable", "The service invoice could not be checked")
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                val adjustment = try { ProjectAdjustmentRepository.create(projectId, type, amount, body.reason, actor, serviceId) }
                    catch (e: IllegalArgumentException) { return@post call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment", e.message ?: "Invalid adjustment") }
                AuditRepository.write(projectId, "service_adjustment", actor, "service=${service.name} ${type.name} amount=$amount reason=${body.reason.trim()}")
                ScribedIntegrationClient.notifyServiceLedger(serviceId, "adjustment-${adjustment.id}")
                call.respond(HttpStatusCode.Created, ServiceAdjustmentView(adjustment.id.toString(), projectId.toString(), serviceId.toString(), type.name, amount.toDouble(), adjustment.reason, actor, adjustment.createdAt.toString()))
            }

            patch("/api/admin/projects/{projectId}/services/{serviceId}") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@patch
                val current = ServiceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@patch call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                val body = runCatching { call.receive<UpdateServiceRequest>() }.getOrNull()
                    ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service update")
                val name = body.name?.trim()
                if (name != null && (!validServiceName(name) || name == "default" && current.name != "default" || current.name == "default" && name != "default")) {
                    return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_service_name", "Service name is invalid or the default service was renamed")
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
                val updated = runCatching {
                    ServiceRepository.update(projectId, serviceId, name, body.accessStatus, body.blockReason, blockReasonCode, body.blockReasonNote)
                }.getOrElse {
                    return@patch call.respondError(HttpStatusCode.Conflict, "service_update_conflict", "The service update conflicts with an existing service")
                } ?: return@patch call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                call.respond(updated.toView())
            }

            delete("/api/admin/projects/{projectId}/services/{serviceId}") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@delete
                val current = ServiceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@delete call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                if (current.name == "default") {
                    return@delete call.respondError(HttpStatusCode.Conflict, "default_service_required", "The default service cannot be deleted")
                }
                if (!ServiceRepository.delete(projectId, serviceId)) {
                    return@delete call.respondError(HttpStatusCode.Conflict, "service_in_use", "Services with deployment, environment, or site history cannot be deleted")
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/api/admin/projects/{projectId}/environment/shared") {
                val projectId = call.serviceProjectId() ?: return@get
                if (ProjectRepository.findActiveById(projectId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val metadata = EnvironmentSetRepository.sharedMetadata(projectId, environment)
                call.respond(SharedEnvironmentMetadataView(
                    projectId.toString(), environment, metadata.latest?.toView(), metadata.versions.map { it.toView() }
                ))
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@get
                if (ServiceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val metadata = EnvironmentSetRepository.serviceMetadata(projectId, serviceId, environment)!!
                call.respond(ServiceEnvironmentMetadataView(
                    projectId.toString(), serviceId.toString(), environment,
                    metadata.configuredSetId?.toString(), metadata.configuredSetVersion,
                    metadata.configuredSharedSetId?.toString(), metadata.configuredSharedSetVersion,
                    metadata.versions.map { it.toView() }
                ))
            }

            put("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@put
                if (ServiceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val body = runCatching { call.receive<EnvironmentWriteRequest>() }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service environment request")
                val environment = normalizeEnvironment(body.environment)
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                if (!validEnvironmentValues(body.values)) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment_values", "Environment variable names or values are invalid")
                }
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
                val result = runCatching {
                    val sharedId = body.sharedEnvironmentSetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (body.sharedEnvironmentSetId != null && sharedId == null) {
                        return@put call.respondError(HttpStatusCode.BadRequest, "invalid_shared_environment_reference", "Shared environment ID is invalid")
                    }
                    DeploymentJobRepository.updateServiceEnvironmentAndDeploy(
                        projectId, serviceId, environment, body.values, actor,
                        sharedEnvironmentSetId = sharedId,
                        sharedEnvironmentSetVersion = body.sharedEnvironmentSetVersion
                    )
                }.getOrElse { error ->
                    return@put call.respondError(HttpStatusCode.Conflict, "service_environment_update_failed", error.message ?: "Service environment could not be saved")
                } ?: return@put call.respondError(HttpStatusCode.Conflict, "source_runtime_required", "Save this service's source/runtime configuration before editing its environment")
                call.respond(HttpStatusCode.Accepted, EnvironmentWriteResponse(
                    result.setId.toString(), result.version, result.deploymentIds.map(UUID::toString)
                ))
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/active-deployment") {
                val (projectId, serviceId) = call.serviceCoordinates() ?: return@get
                if (ServiceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val rawEnvironment = call.request.queryParameters["environment"]
                val environment = if (rawEnvironment == null) "production" else normalizeEnvironment(rawEnvironment)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                val active = EnvironmentSetRepository.activeDeployment(serviceId, environment)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "active_deployment_not_found", "No active deployment exists for this service and environment")
                call.respond(active.toView())
            }
        }
    }
}

private suspend fun ApplicationCall.serviceProjectId(): UUID? {
    val raw = parameters["projectId"]
    if (raw == null) {
        respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is required")
        return null
    }
    val projectId = runCatching { UUID.fromString(raw) }.getOrNull() ?: run {
        respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is invalid")
        return null
    }
    if (ProjectRepository.findActiveById(projectId) == null) {
        respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        return null
    }
    return projectId
}

private suspend fun ApplicationCall.serviceCoordinates(): Pair<UUID, UUID>? {
    val projectId = serviceProjectId() ?: return null
    val serviceId = parameters["serviceId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: run {
            respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
            return null
        }
    return projectId to serviceId
}

private fun validServiceName(value: String) = value.length in 1..63 && value.matches(Regex("^[a-z][a-z0-9_-]*$"))
private fun normalizeEnvironment(value: String) = value.trim().lowercase().takeIf { it.matches(Regex("^[a-z][a-z0-9_-]{0,31}$")) }
private fun validEnvironmentValues(values: Map<String, String>) = values.all { (key, value) ->
    key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !key.contains('=') && !value.contains('\u0000')
}

private fun ServiceRepository.ServiceRecord.toView() = ServiceAdminView(
    id.toString(), projectId.toString(), name, accessStatus, blockReason,
    blockReasonCode?.value, blockReasonNote
)
private fun EnvironmentSetRepository.VersionMetadata.toView() = EnvironmentVersionView(id.toString(), version, environment, keys, createdAt.toString(), createdBy)
private fun EnvironmentSetRepository.ActiveDeploymentInspection.toView() = ActiveDeploymentInspectionView(
    deploymentId.toString(), containerName, serviceId.toString(), environment, imageName, imageTag, imageDigest, commitSha,
    activeAt?.toString(), sharedSetId?.toString(), sharedSetVersion, serviceSetId?.toString(), serviceSetVersion,
    variables.map { FingerprintedEnvironmentVariableView(it.key, it.source, it.fingerprint) }
)
