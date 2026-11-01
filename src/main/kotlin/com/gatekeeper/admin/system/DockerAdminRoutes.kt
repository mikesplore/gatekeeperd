package com.gatekeeper.admin.system


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.HttpApiException
import com.gatekeeper.docker.CreateNetworkRequest
import com.gatekeeper.docker.DockerAdminApplicationService
import com.gatekeeper.docker.PullImageRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureDockerAdminRoutes() {
    val docker = get<DockerAdminApplicationService>()
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/containers") {
                val query = call.request.queryParameters
                val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 500
                call.respond(docker.containers(query["search"]?.trim()?.lowercase(), query["state"], offset, limit))
            }
            get("/api/admin/containers/{name}") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.container(name))
            }
            get("/api/admin/containers/{name}/credentials") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.credentials(name))
            }
            get("/api/admin/containers/{name}/logs") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                val tail = call.request.queryParameters["tail"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                call.respond(docker.logs(name, tail))
            }
            post("/api/admin/containers/{name}/start") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.start(name))
            }
            post("/api/admin/containers/{name}/stop") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.stop(name))
            }
            post("/api/admin/containers/{name}/restart") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.restart(name))
            }
            get("/api/admin/containers/{name}/health") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.health(name))
            }
            get("/api/admin/networks") {
                val query = call.request.queryParameters
                val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                call.respond(docker.networks(query["q"]?.trim()?.lowercase(), offset, limit))
            }
            post("/api/admin/networks") {
                val body = call.receive<CreateNetworkRequest>()
                docker.createNetwork(body)
                call.respond(HttpStatusCode.Created, mapOf("name" to body.name, "driver" to body.driver))
            }
            delete("/api/admin/networks/{name}") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "invalid_request", "Network name is required")
                docker.deleteNetwork(name)
                call.respond(mapOf("status" to "deleted", "name" to name))
            }
            get("/api/admin/volumes") {
                val query = call.request.queryParameters
                val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                call.respond(docker.volumes(query["q"]?.trim()?.lowercase(), offset, limit))
            }
            post("/api/admin/volumes") {
                val body = call.receive<CreateNetworkRequest>()
                val driver = docker.createVolume(body)
                call.respond(HttpStatusCode.Created, mapOf("name" to body.name, "driver" to driver))
            }
            delete("/api/admin/volumes/{name}") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "invalid_request", "Volume name is required")
                docker.deleteVolume(name)
                call.respond(mapOf("status" to "deleted", "name" to name))
            }
            post("/api/admin/images/pull") {
                val body = call.receive<PullImageRequest>()
                val image = body.image.trim()
                val tag = body.tag.trim().ifBlank { "latest" }
                docker.pullImage(image, tag, body.pullViaCli)
                call.respond(mapOf("status" to "pulled", "image" to "$image:$tag"))
            }
        }
    }
}
