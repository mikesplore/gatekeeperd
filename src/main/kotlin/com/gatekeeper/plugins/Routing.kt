package com.gatekeeper.plugins

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.DockerService
import com.gatekeeper.docker.PullImageRequest
import com.gatekeeper.api.InputValidators
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.lang.Runtime

private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.Routing")

private suspend fun ApplicationCall.requireContainerName(): String? {
    val name = parameters["name"]
    if (name.isNullOrBlank()) {
        respondError(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
        return null
    }
    if (!InputValidators.isValidContainerName(name)) {
        respondError(HttpStatusCode.BadRequest, "invalid_container_name", "Invalid container name")
        return null
    }
    return name
}

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

        get("/api/health/live") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/api/health/ready") {
            val database = DatabaseFactory.isHealthy()
            val redis = RedisService.isHealthy()
            val ready = database && redis
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "status" to if (ready) "ready" else "not_ready",
                    "dependencies" to mapOf("database" to database, "redis" to redis)
                )
            )
        }

        // Admin routes (JWT-protected)
        authenticate("auth-jwt") {
            get("/api/admin/containers") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val search = call.request.queryParameters["search"]?.trim()?.lowercase()
                val state = call.request.queryParameters["state"]
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 500
                val containers = svc.listContainers(all = true).asSequence()
                    .filter { search.isNullOrBlank() || it.name.lowercase().contains(search) || it.image.lowercase().contains(search) }
                    .filter { state.isNullOrBlank() || it.state.equals(state, ignoreCase = true) }
                    .drop(offset).take(limit).toList()
                call.respond(containers)
            }

            get("/api/admin/containers/{name}") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val name = call.requireContainerName() ?: return@get
                val container = svc.getContainer(name)
                if (container != null) {
                    call.respond(container)
                } else {
                    call.respondError(HttpStatusCode.NotFound, "container_not_found", "Container not found")
                }
            }

            post("/api/admin/containers/{name}/start") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val name = call.requireContainerName() ?: return@post
                try {
                    svc.startContainer(name)
                    call.respond(mapOf("status" to "started", "container" to name))
                } catch (e: Exception) {
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "container_start_failed",
                        e.message ?: "Failed to start container"
                    )
                }
            }

            post("/api/admin/containers/{name}/stop") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val name = call.requireContainerName() ?: return@post
                try {
                    svc.stopContainer(name)
                    call.respond(mapOf("status" to "stopped", "container" to name))
                } catch (e: Exception) {
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "container_stop_failed",
                        e.message ?: "Failed to stop container"
                    )
                }
            }

            post("/api/admin/containers/{name}/restart") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val name = call.requireContainerName() ?: return@post
                try {
                    svc.restartContainer(name)
                    call.respond(mapOf("status" to "restarted", "container" to name))
                } catch (e: Exception) {
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "container_restart_failed",
                        e.message ?: "Failed to restart container"
                    )
                }
            }

            get("/api/admin/containers/{name}/health") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val name = call.requireContainerName() ?: return@get
                val health = svc.containerHealth(name)
                call.respond(mapOf("container" to name, "health" to health))
            }

            get("/api/admin/networks") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val networks = svc.listNetworks()
                call.respond(networks)
            }

            post("/api/admin/images/pull") {
                val svc = dockerService
                if (svc == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val body = try {
                    call.receive<PullImageRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@post
                }
                val image = body.image.trim()
                if (image.isBlank() || !InputValidators.isValidImageName(image)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A valid image name is required")
                    return@post
                }
                val tag = body.tag.trim().ifBlank { "latest" }
                try {
                    val fullRef = "$image:$tag"
                    if (body.pullViaCli || AppConfig.dockerPullViaCli) {
                        svc.pullImageViaCli(fullRef, AppConfig.dockerSocket)
                    } else {
                        svc.pullImage(image, tag)
                    }
                    call.respond(mapOf("status" to "pulled", "image" to "$image:$tag"))
                } catch (e: Exception) {
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "image_pull_failed",
                        e.message ?: "Failed to pull image"
                    )
                }
            }
        }
    }
}
