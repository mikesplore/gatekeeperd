package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.deployment.CreateDeploymentRequest
import com.gatekeeper.deployment.DeploymentJobResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID

fun Application.configureDeploymentAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            post("/api/admin/deployments") {
                val request = runCatching { call.receive<CreateDeploymentRequest>() }.getOrNull() ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment request")
                    return@post
                }
                if (!request.repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) ||
                    !request.gitRef.matches(Regex("^[A-Za-z0-9._/-]+$")) ||
                    !request.imageName.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) ||
                    !request.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$"))) {
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
        }
    }
}

private fun com.gatekeeper.db.repositories.DeploymentJobRecord.toResponse() = DeploymentJobResponse(
    id.toString(), repository, gitRef, registry, imageName, imageTag, status, currentStep, logs, commitSha, imageDigest,
    errorMessage, createdAt.toString(), startedAt?.toString(), completedAt?.toString(), updatedAt.toString()
)
