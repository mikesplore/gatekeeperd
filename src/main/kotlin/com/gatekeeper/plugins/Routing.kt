package com.gatekeeper.plugins

import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.DockerService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.Routing")

fun Application.configureRouting() {
    val internalNetwork = AppConfig.internalNetwork

    // Initialize Docker lazily — may not be available in all environments
    val dockerService: DockerService? = try {
        DockerService(AppConfig.dockerSocket).also { svc ->
            svc.createNetworkIfMissing(internalNetwork)
            logger.info("Docker initialized successfully")
        }
    } catch (e: Exception) {
        logger.warn("Docker not available (non-fatal): ${e.message}")
        null
    }

    routing {
        // Health check — no auth required
        get("/api/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // Admin routes (JWT-protected)
        authenticate("auth-jwt") {
            get("/api/admin/containers") {
                val svc = dockerService
                if (svc == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                    return@get
                }
                val containers = svc.listContainers(all = true)
                call.respond(containers)
            }

            get("/api/admin/containers/{name}") {
                val svc = dockerService
                if (svc == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                    return@get
                }
                val name = call.parameters["name"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "missing container name")
                )
                val container = svc.getContainer(name)
                if (container != null) {
                    call.respond(container)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "container not found"))
                }
            }
        }
    }
}