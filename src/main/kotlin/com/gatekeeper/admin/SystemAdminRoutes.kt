package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.docker.DockerCleanupService
import com.gatekeeper.deployment.DeploymentTriggerService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureSystemAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            post("/api/admin/system/prune") {
                val dryRun = call.request.queryParameters["dryRun"]?.toBooleanStrictOrNull() ?: false
                val prefix = call.request.queryParameters["imagePrefix"]?.takeIf { it.isNotBlank() }
                if (prefix != null && !prefix.matches(Regex("^[A-Za-z0-9_.:/-]+$"))) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_image_prefix", "Image prefix is invalid")
                    return@post
                }
                val result = get<DockerCleanupService>().pruneProjectImages(prefix, emptySet(), dryRun, "admin")
                call.respond(result)
            }
            post("/api/admin/system/deploy") {
                val result = get<DeploymentTriggerService>().trigger()
                if (!result.started) {
                    call.respondError(
                        HttpStatusCode.ServiceUnavailable,
                        "deployment_trigger_failed",
                        result.error ?: "The deployment checker could not be started"
                    )
                    return@post
                }
                call.respond(
                    mapOf(
                        "started" to true,
                        "message" to "Deployment check started; refresh deployment status shortly."
                    )
                )
            }
        }
    }
}
