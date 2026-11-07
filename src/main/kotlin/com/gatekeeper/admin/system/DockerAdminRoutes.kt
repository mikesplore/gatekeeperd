package com.gatekeeper.admin.system


import com.gatekeeper.api.HttpApiException
import com.gatekeeper.docker.CreateNetworkRequest
import com.gatekeeper.docker.DockerAdminApplicationService
import com.gatekeeper.docker.PullImageRequest
import com.gatekeeper.docker.MoveContainerNetworkRequest
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.ContainerCreatePlanResult
import com.gatekeeper.docker.ContainerCreationTracker
import com.gatekeeper.docker.ContainerWizardValidateResponse
import com.gatekeeper.docker.PortsAvailabilityRequest
import com.gatekeeper.docker.PortsAvailabilityResponse
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import org.koin.ktor.ext.get

fun Application.configureDockerAdminRoutes() {
    val docker = get<DockerAdminApplicationService>()
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            get("/api/admin/containers") {
                val query = call.request.queryParameters
                val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 500
                call.respond(docker.containers(query["search"]?.trim()?.lowercase(), query["state"], offset, limit))
            }
            get("/api/admin/containers/wizard/context") {
                call.respond(docker.containerWizardContext())
            }
            post("/api/admin/containers/wizard/validate") {
                val request = try { call.receive<CreateContainerRequest>() } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }
                when (val result = docker.containerCreatePlan(request)) {
                    is ContainerCreatePlanResult.Err -> if (result.data != null)
                        call.respondErrorWithData(result.status, result.code, result.message, result.data)
                    else call.respondError(result.status, result.code, result.message)
                    is ContainerCreatePlanResult.Ok -> {
                        val plan = result.plan
                        call.respond(ContainerWizardValidateResponse(true, "Validated successfully (no changes applied)", plan.normalizedRequest, plan.imageExists, plan.willPullImage, plan.networkExists, plan.willCreateInternalNetworkIfMissing, warnings = plan.warnings))
                    }
                }
            }
            post("/api/admin/containers/wizard/ports/check") {
                val request = try { call.receive<PortsAvailabilityRequest>() } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }
                val ports = request.hostPorts.filter { it in 1..65535 }.toSet()
                if (ports.size != request.hostPorts.size) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "hostPorts must be between 1 and 65535")
                    return@post
                }
                val conflicts = ports.intersect(docker.hostPortsInUse()).sorted()
                call.respond(PortsAvailabilityResponse(conflicts.isEmpty(), conflicts))
            }
            post("/api/admin/containers/create") {
                val request = try { call.receive<CreateContainerRequest>() } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }
                when (val result = docker.containerCreatePlan(request)) {
                    is ContainerCreatePlanResult.Err -> if (result.data != null)
                        call.respondErrorWithData(result.status, result.code, result.message, result.data)
                    else call.respondError(result.status, result.code, result.message)
                    is ContainerCreatePlanResult.Ok -> {
                        val plan = result.plan
                        val job = ContainerCreationTracker.create(plan.normalizedRequest.name)
                        call.application.launch(Dispatchers.IO) {
                            try {
                                docker.runContainerCreate(plan) { status -> ContainerCreationTracker.update(job.id, status) }
                                ContainerCreationTracker.update(job.id, "succeeded")
                            } catch (error: Exception) {
                                ContainerCreationTracker.update(job.id, "failed", error.message ?: "Container creation failed")
                            }
                        }
                        call.respond(HttpStatusCode.Accepted, mapOf("id" to job.id, "status" to "queued", "name" to plan.normalizedRequest.name))
                    }
                }
            }
            get("/api/admin/containers/creation/{id}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
                val job = id?.let(ContainerCreationTracker::find)
                if (job == null) call.respondError(HttpStatusCode.NotFound, "container_creation_not_found", "Container creation operation not found")
                else call.respond(job)
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
            post("/api/admin/containers/{name}/delete") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.delete(name))
            }
            get("/api/admin/containers/{name}/health") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.health(name))
            }
            post("/api/admin/containers/{name}/networks/move") {
                val name = call.parameters["name"] ?: throw HttpApiException(HttpStatusCode.BadRequest, "missing_container_name", "Missing container name path parameter")
                call.respond(docker.moveContainerNetwork(name, call.receive<MoveContainerNetworkRequest>()))
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
