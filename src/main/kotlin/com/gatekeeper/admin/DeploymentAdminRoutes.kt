package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.deployment.CreateDeploymentRequest
import com.gatekeeper.deployment.DeploymentJobResponse
import com.gatekeeper.deployment.DeploymentWorker
import com.gatekeeper.deployment.DeploymentReconciliationService
import com.gatekeeper.config.AppConfig
import com.gatekeeper.deployment.UpdateDeploymentConfigurationRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.api.dto.toResponse

fun Application.configureDeploymentAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/deployments/reconciliation") {
                call.respond(DeploymentReconciliationService.production(AppConfig.dockerSocket).report())
            }
            get("/api/admin/deployments") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                call.respond(DeploymentJobRepository.list(limit, offset).map { it.toResponse() })
            }
            post("/api/admin/deployments") {
                val request = runCatching { call.receive<CreateDeploymentRequest>() }.getOrNull() ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment request")
                    return@post
                }
                if (!request.repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) ||
                    !request.gitRef.matches(Regex("^[A-Za-z0-9._/-]+$")) ||
                    !request.imageName.matches(Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")) ||
                    !request.registry.matches(Regex("^(docker\\.io|[A-Za-z0-9.-]+(:[0-9]{1,5})?)$")) ||
                    !request.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$")) ||
                    request.environment.isBlank() ||
                    (request.env.keys + request.secretEnv.keys).any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } ||
                    request.volumes.any { it.hostPath.isBlank() || it.containerPath.isBlank() }) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_target", "Repository, ref, image name, or tag is invalid")
                    return@post
                }
                if (request.secretEnv.isNotEmpty() && !SecretValueCipher.isConfigured()) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "deployment_secrets_unconfigured", "Deployment secret encryption is not configured")
                    return@post
                }
                val id = DeploymentJobRepository.create(request)
                call.respond(HttpStatusCode.Accepted, mapOf("id" to id.toString(), "environment" to request.environment.trim(), "status" to "queued"))
            }
            post("/api/admin/projects/{projectId}/deployment-configuration") {
                val projectId = call.parameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (projectId == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Invalid project ID")
                    return@post
                }
                val request = runCatching { call.receive<CreateDeploymentRequest>() }.getOrNull() ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment configuration request")
                    return@post
                }
                if (!request.repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) ||
                    !request.gitRef.matches(Regex("^[A-Za-z0-9._/-]+$")) ||
                    !request.imageName.matches(Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")) ||
                    !request.registry.matches(Regex("^(docker\\.io|[A-Za-z0-9.-]+(:[0-9]{1,5})?)$")) ||
                    !request.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$")) ||
                    request.environment.isBlank() ||
                    (request.env.keys + request.secretEnv.keys).any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } ||
                    request.volumes.any { it.hostPath.isBlank() || it.containerPath.isBlank() }) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_target", "Repository, ref, image name, or tag is invalid")
                    return@post
                }
                if (request.secretEnv.isNotEmpty() && !SecretValueCipher.isConfigured()) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "deployment_secrets_unconfigured", "Deployment secret encryption is not configured")
                    return@post
                }
                val configurationId = runCatching { DeploymentJobRepository.createConfiguration(projectId, request) }.getOrElse { error ->
                    when {
                        error.message == "Project not found" -> call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                        error.message == "Project already has a deployment configuration" -> call.respondError(HttpStatusCode.Conflict, "deployment_configuration_exists", "Project already has a deployment configuration")
                        else -> call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration", error.message ?: "Invalid deployment configuration")
                    }
                    return@post
                }
                call.respond(HttpStatusCode.Created, mapOf("id" to configurationId.toString(), "projectId" to projectId.toString(), "environment" to request.environment.trim(), "status" to "configured", "secretEnv" to "write-only"))
            }
            patch("/api/admin/deployment-configurations/{id}") { updateConfiguration(call) }
            put("/api/admin/deployment-configurations/{id}") { updateConfiguration(call) }
            post("/api/admin/deployment-configurations/{id}/redeploy") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id == null || !DeploymentJobRepository.configurationExists(id)) { call.respondError(HttpStatusCode.NotFound, "deployment_configuration_not_found", "Deployment configuration not found"); return@post }
                val executionId = DeploymentJobRepository.redeployConfiguration(id) ?: run { call.respondError(HttpStatusCode.NotFound, "deployment_configuration_not_found", "Deployment configuration not found"); return@post }
                call.respond(HttpStatusCode.Accepted, mapOf("id" to executionId.toString(), "status" to "queued"))
            }
            get("/api/admin/deployments/{id}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
                    return@get
                }
                val job = DeploymentJobRepository.find(id) ?: run {
                    call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found")
                    return@get
                }
                call.respond(job.toResponse())
            }
            get("/api/admin/deployments/{id}/audit") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID"); return@get }
                if (DeploymentJobRepository.find(id) == null) { call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found"); return@get }
                call.respond(AuditRepository.findByJobId(id).map { it.toResponse() })
            }
            post("/api/admin/deployments/{id}/cancel") { changeDeployment(call, "cancel") }
            post("/api/admin/deployments/{id}/retry") { changeDeployment(call, "retry") }
            post("/api/admin/deployments/{id}/rollback") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID"); return@post }
                val rollbackId = DeploymentWorker.rollback(id)
                if (rollbackId == null) call.respondError(HttpStatusCode.Conflict, "rollback_unavailable", "Deployment cannot be used as a rollback target")
                else call.respond(HttpStatusCode.Accepted, mapOf("id" to rollbackId.toString(), "rolledBackToDeploymentId" to id.toString(), "status" to "queued"))
            }
        }
    }
}

private suspend fun updateConfiguration(call: ApplicationCall) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration_id", "Invalid deployment configuration ID"); return }
    val request = runCatching { call.receive<UpdateDeploymentConfigurationRequest>() }.getOrNull()
    if (request == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment configuration request"); return }
    if (request.secretEnv != null && !SecretValueCipher.isConfigured()) { call.respondError(HttpStatusCode.ServiceUnavailable, "deployment_secrets_unconfigured", "Deployment secret encryption is not configured"); return }
    runCatching { DeploymentJobRepository.updateConfiguration(id, request) }.onFailure { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration", it.message ?: "Invalid deployment configuration"); return }.getOrThrow()
    call.respond(mapOf("id" to id.toString(), "status" to "updated", "secretEnv" to "write-only"))
}

private suspend fun changeDeployment(call: ApplicationCall, action: String) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID"); return }
    val changed = if (action == "cancel") DeploymentWorker.cancel(id) else DeploymentJobRepository.retry(id)
    if (!changed) call.respondError(HttpStatusCode.Conflict, "deployment_state_conflict", "Deployment cannot be $action in its current state")
    else call.respond(mapOf("id" to id.toString(), "status" to if (action == "cancel") "cancelled" else "queued"))
}

private fun com.gatekeeper.db.repositories.DeploymentJobRecord.toResponse() = DeploymentJobResponse(
    id.toString(), repository, gitRef, registry, imageName, imageTag, status, currentStep, logs, commitSha, imageDigest,
    errorMessage, createdAt.toString(), startedAt?.toString(), completedAt?.toString(), updatedAt.toString(), previousImage != null, triggerSource, env,
    secretEnv.keys.sorted().associateWith { "••••••••" }, volumes, projectId?.toString(), environment,
    com.gatekeeper.deployment.DeploymentApplicationService.rollbackTargetId(id)?.toString()
)
