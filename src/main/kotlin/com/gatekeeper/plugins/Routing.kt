package com.gatekeeper.plugins

import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.DockerService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.lang.Runtime

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
        // Shutdown hooks after Docker init
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { dockerService?.close() }
        })

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

            post("/api/admin/containers/{name}/start") {
                val svc = dockerService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing container name"))
                try {
                    svc.startContainer(name)
                    call.respond(mapOf("status" to "started", "container" to name))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed to start")))
                }
            }

            post("/api/admin/containers/{name}/stop") {
                val svc = dockerService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing container name"))
                try {
                    svc.stopContainer(name)
                    call.respond(mapOf("status" to "stopped", "container" to name))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed to stop")))
                }
            }

            post("/api/admin/containers/{name}/restart") {
                val svc = dockerService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing container name"))
                try {
                    svc.restartContainer(name)
                    call.respond(mapOf("status" to "restarted", "container" to name))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed to restart")))
                }
            }

            get("/api/admin/containers/{name}/health") {
                val svc = dockerService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing container name"))
                val health = svc.containerHealth(name)
                call.respond(mapOf("container" to name, "health" to health))
            }

            get("/api/admin/networks") {
                val svc = dockerService ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                val networks = svc.listNetworks()
                call.respond(networks)
            }

            post("/api/admin/images/pull") {
                val svc = dockerService ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Docker not available"))
                val body = try {
                    call.receiveText()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid body"))
                    return@post
                }
                // Simple JSON parse: {"image":"name","tag":"latest"}
                val image = Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                if (image == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "image required"))
                    return@post
                }
                val tag = Regex("\"tag\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1) ?: "latest"
                try {
                    svc.pullImage(image, tag)
                    call.respond(mapOf("status" to "pulled", "image" to "$image:$tag"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed to pull")))
                }
            }
        }
    }
}
