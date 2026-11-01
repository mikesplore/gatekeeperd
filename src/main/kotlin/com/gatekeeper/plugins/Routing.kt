package com.gatekeeper.plugins

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.docker.PullImageRequest
import com.gatekeeper.docker.ContainersListResponse
import com.gatekeeper.docker.ContainerLogsResponse
import com.gatekeeper.docker.ContainerPresetCredentialsResponse
import com.gatekeeper.docker.CreateNetworkRequest
import com.gatekeeper.docker.NetworkPage
import com.gatekeeper.docker.VolumePage
import com.gatekeeper.deployment.DeploymentVersionService
import com.gatekeeper.api.InputValidators
import kotlinx.serialization.Serializable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

@Serializable
data class HealthReadinessResponse(
    val status: String,
    val dependencies: HealthDependencyStatus
)

@Serializable
data class HealthDependencyStatus(
    val database: Boolean,
    val redis: Boolean,
    val migrations: Boolean
)

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
    val dockerService = get<DockerGateway>()

    routing {
        // Health check — no auth required
        get("/api/health") {
            call.respond(mapOf("status" to "ok"))
        }

        // Public and read-only: compares local deployment metadata, without a GitHub request.
        get("/api/version") {
            call.respond(DeploymentVersionService().current())
        }

        get("/api/health/live") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/health/live") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/api/health/ready") {
            val database = DatabaseFactory.isHealthy()
            val redis = RedisService.isHealthy()
            val migrations = DatabaseFactory.migrationsHealthy()
            val ready = database && redis && migrations
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthReadinessResponse(
                    status = if (ready) "ready" else "not_ready",
                    dependencies = HealthDependencyStatus(database, redis, migrations)
                )
            )
        }

        get("/health/ready") {
            val database = DatabaseFactory.isHealthy()
            val redis = RedisService.isHealthy()
            val migrations = DatabaseFactory.migrationsHealthy()
            val ready = database && redis && migrations
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthReadinessResponse(
                    status = if (ready) "ready" else "not_ready",
                    dependencies = HealthDependencyStatus(database, redis, migrations)
                )
            )
        }

        // Admin routes (JWT-protected)
        authenticate("auth-jwt") {
            get("/api/admin/containers") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val search = call.request.queryParameters["search"]?.trim()?.lowercase()
                val state = call.request.queryParameters["state"]
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 500
                val filteredContainers = dockerService.listContainers(all = true).asSequence()
                    .filter { search.isNullOrBlank() || it.name.lowercase().contains(search) || it.image.lowercase().contains(search) }
                    .filter { state.isNullOrBlank() || it.state.equals(state, ignoreCase = true) }
                    .toList()
                call.respond(
                    ContainersListResponse(
                        containers = filteredContainers.drop(offset).take(limit),
                        total = filteredContainers.size,
                        limit = limit,
                        offset = offset
                    )
                )
            }

            get("/api/admin/containers/{name}") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val name = call.requireContainerName() ?: return@get
                val container = dockerService.getContainer(name)
                if (container != null) {
                    call.respond(container)
                } else {
                    call.respondError(HttpStatusCode.NotFound, "container_not_found", "Container not found")
                }
            }

            get("/api/admin/containers/{name}/credentials") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val name = call.requireContainerName() ?: return@get
                val credentials = dockerService.getContainerPresetCredentials(name)
                if (credentials != null) call.respond(credentials)
                else call.respondError(HttpStatusCode.NotFound, "preset_credentials_not_found", "No supported database or Redis preset credentials were found for this container")
            }

            get("/api/admin/containers/{name}/logs") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val name = call.requireContainerName() ?: return@get
                val tail = call.request.queryParameters["tail"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                runCatching { dockerService.containerLogs(name, tail) }
                    .onSuccess { call.respond(ContainerLogsResponse(name, tail, it)) }
                    .onFailure { call.respondError(HttpStatusCode.Conflict, "container_logs_failed", it.message ?: "Unable to read container logs") }
            }

            post("/api/admin/containers/{name}/start") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val name = call.requireContainerName() ?: return@post
                try {
                    dockerService.startContainer(name)
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
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val name = call.requireContainerName() ?: return@post
                try {
                    dockerService.stopContainer(name)
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
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val name = call.requireContainerName() ?: return@post
                try {
                    dockerService.restartContainer(name)
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
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val name = call.requireContainerName() ?: return@get
                val health = dockerService.containerHealth(name)
                call.respond(mapOf("container" to name, "health" to health))
            }

            get("/api/admin/networks") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }
                val search = call.request.queryParameters["q"]?.trim()?.lowercase()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val all = dockerService.listNetworks().filter { search.isNullOrBlank() || it.name.lowercase().contains(search) }
                val rows = all.drop(offset).take(limit)
                call.respond(NetworkPage(rows, all.size, limit, offset, offset + rows.size < all.size))
            }

            post("/api/admin/networks") {
                val svc = dockerService.takeIf { it.available } ?: run { call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available"); return@post }
                val body = runCatching { call.receive<CreateNetworkRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid network request"); return@post }
                runCatching { svc.createNetwork(body.name, body.driver); call.respond(HttpStatusCode.Created, mapOf("name" to body.name, "driver" to body.driver)) }.onFailure { call.respondError(HttpStatusCode.Conflict, "network_create_failed", it.message ?: "Unable to create network") }
            }
            delete("/api/admin/networks/{name}") {
                val svc = dockerService.takeIf { it.available } ?: run { call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available"); return@delete }
                val name = call.parameters["name"] ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Network name is required"); return@delete }
                runCatching { svc.deleteNetwork(name); call.respond(mapOf("status" to "deleted", "name" to name)) }.onFailure { call.respondError(HttpStatusCode.Conflict, "network_delete_failed", it.message ?: "Unable to delete network") }
            }
            get("/api/admin/volumes") { dockerService.takeIf { it.available }?.let { svc ->
                val search = call.request.queryParameters["q"]?.trim()?.lowercase()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                runCatching {
                    val all = svc.listVolumes().filter { search.isNullOrBlank() || it.name.lowercase().contains(search) }
                    val rows = all.drop(offset).take(limit)
                    call.respond(VolumePage(rows, all.size, limit, offset, offset + rows.size < all.size))
                }.onFailure { call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", it.message ?: "Unable to list Docker volumes") }
            } ?: call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available") }
            post("/api/admin/volumes") {
                val svc = dockerService.takeIf { it.available } ?: run { call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available"); return@post }
                val body = runCatching { call.receive<CreateNetworkRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid volume request"); return@post }
                val driver = body.driver.trim().takeIf { it.isNotBlank() && !it.equals("bridge", ignoreCase = true) } ?: "local"
                runCatching { svc.createVolume(body.name, driver); call.respond(HttpStatusCode.Created, mapOf("name" to body.name, "driver" to driver)) }.onFailure { call.respondError(HttpStatusCode.Conflict, "volume_create_failed", it.message ?: "Unable to create volume") }
            }
            delete("/api/admin/volumes/{name}") {
                val svc = dockerService.takeIf { it.available } ?: run { call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available"); return@delete }
                val name = call.parameters["name"] ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Volume name is required"); return@delete }
                runCatching { svc.deleteVolume(name); call.respond(mapOf("status" to "deleted", "name" to name)) }.onFailure { call.respondError(HttpStatusCode.Conflict, "volume_delete_failed", it.message ?: "Unable to delete volume") }
            }

            post("/api/admin/images/pull") {
                if (!dockerService.available) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }
                val body = try {
                    call.receive<PullImageRequest>()
                } catch (_: Exception) {
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
                    val dockerSettings = get<AppSettings>().docker
                    if (body.pullViaCli || dockerSettings.pullViaCli) {
                        dockerService.pullImageViaCli(fullRef, dockerSettings.socket)
                    } else {
                        dockerService.pullImage(image, tag)
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
