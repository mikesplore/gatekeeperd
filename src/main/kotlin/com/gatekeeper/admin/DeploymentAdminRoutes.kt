package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.deployment.CreateDeploymentRequest
import com.gatekeeper.deployment.DeploymentJobResponse
import com.gatekeeper.deployment.DeploymentWorker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID
import com.gatekeeper.db.repositories.AuditRepository

fun Application.configureDeploymentAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
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
                    !request.imageName.matches(Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$")) ||
                    !request.registry.matches(Regex("^(docker\\.io|[A-Za-z0-9.-]+(:[0-9]{1,5})?)$")) ||
                    !request.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$")) || request.env.keys.any { it.isBlank() || it.contains('=') || it.contains('\u0000') } || request.volumes.any { it.hostPath.isBlank() || it.containerPath.isBlank() }) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_target", "Repository, ref, image name, or tag is invalid")
                    return@post
                }
                val id = DeploymentJobRepository.create(request)
                call.respond(HttpStatusCode.Accepted, mapOf("id" to id.toString(), "status" to "queued"))
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
                call.respond(AuditRepository.findByJobId(id))
            }
            post("/api/admin/deployments/{id}/cancel") { changeDeployment(call, "cancel") }
            post("/api/admin/deployments/{id}/retry") { changeDeployment(call, "retry") }
            post("/api/admin/deployments/{id}/rollback") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID"); return@post }
                if (!DeploymentWorker.rollback(id)) call.respondError(HttpStatusCode.Conflict, "rollback_unavailable", "No healthy previous container is available or rollback failed")
                else call.respond(mapOf("id" to id.toString(), "status" to "rolled_back"))
            }
        }
    }
}

private suspend fun changeDeployment(call: ApplicationCall, action: String) {
    val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID"); return }
    val changed = if (action == "cancel") DeploymentJobRepository.cancel(id) else DeploymentJobRepository.retry(id)
    if (!changed) call.respondError(HttpStatusCode.Conflict, "deployment_state_conflict", "Deployment cannot be $action in its current state")
    else call.respond(mapOf("id" to id.toString(), "status" to if (action == "cancel") "cancelled" else "queued"))
}

private fun com.gatekeeper.db.repositories.DeploymentJobRecord.toResponse() = DeploymentJobResponse(
    id.toString(), repository, gitRef, registry, imageName, imageTag, status, currentStep, logs, commitSha, imageDigest,
    errorMessage, createdAt.toString(), startedAt?.toString(), completedAt?.toString(), updatedAt.toString(), env, volumes
)
