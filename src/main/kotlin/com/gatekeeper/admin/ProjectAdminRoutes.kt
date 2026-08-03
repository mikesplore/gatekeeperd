package com.gatekeeper.admin

import com.gatekeeper.api.dto.AuditLogResponse
import com.gatekeeper.api.dto.PaymentResponse
import com.gatekeeper.api.dto.ProjectDetailResponse
import com.gatekeeper.api.dto.ProjectResponse
import com.gatekeeper.api.dto.StatusChangeResponse
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.CreateContainerResponse
import com.gatekeeper.docker.DeleteImageRequest
import com.gatekeeper.docker.DeleteImageResponse
import com.gatekeeper.docker.DockerService
import com.gatekeeper.docker.ImageStatusRequest
import com.gatekeeper.docker.ImageStatusResponse
import com.gatekeeper.docker.PortsAvailabilityRequest
import com.gatekeeper.docker.PortsAvailabilityResponse
import com.gatekeeper.paystack.ProjectPaymentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.math.BigDecimal

private val logger = LoggerFactory.getLogger("com.gatekeeper.admin.ProjectAdminRoutes")

private data class ParsedImageRef(
    val repository: String,
    val tag: String
) {
    val normalized: String get() = "$repository:$tag"
}

private fun parseImageRef(raw: String): ParsedImageRef? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.contains("@")) return null // digests not supported in admin wizard yet

    val lastSlash = trimmed.lastIndexOf('/')
    val lastColon = trimmed.lastIndexOf(':')
    val hasTag = lastColon > lastSlash

    val repository = if (hasTag) trimmed.substring(0, lastColon) else trimmed
    val tag = if (hasTag) trimmed.substring(lastColon + 1) else "latest"

    if (!InputValidators.isValidImageName(repository)) return null
    if (tag.isBlank() || !Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$").matches(tag)) return null

    return ParsedImageRef(repository = repository, tag = tag)
}

@Serializable
data class CreateProjectRequest(
    val slug: String,
    val name: String,
    val domain: String,
    val containerName: String,
    val type: String,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val amountDue: Double? = null,
    val currency: String = "KES",
    val dueDate: String? = null,
    val gracePeriodDays: Int = 3
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val domain: String? = null,
    val containerName: String? = null,
    val type: String? = null,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val amountDue: Double? = null,
    val currency: String? = null,
    val dueDate: String? = null,
    val gracePeriodDays: Int? = null
)

@Serializable
data class InitializePaymentRequest(val email: String)

@Serializable
data class InitializePaymentResponse(val payment_link: String)

@Serializable
data class StatusChangeRequest(val reason: String)

fun Application.configureProjectAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/projects") {
                val projects = ProjectRepository.findAll().map { it.toResponse() }
                call.respond(projects)
            }

            get("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }
                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val payments = PaymentRepository.findByProjectId(project.id)
                val auditLog = AuditRepository.findByProjectId(project.id)
                call.respond(
                    ProjectDetailResponse(
                        project = project.toResponse(),
                        payments = payments.map { it.toResponse() },
                        audit_log = auditLog.map { it.toResponse() }
                    )
                )
            }

            post("/api/admin/projects") {
                val body = try {
                    call.receive<CreateProjectRequest>()
                } catch (e: Exception) {
                    logger.warn("Failed to parse create project request", e)
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@post
                }

                if (body.slug.isBlank() || body.name.isBlank() || body.domain.isBlank() || body.containerName.isBlank()) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "slug, name, domain, and containerName are required"
                    )
                    return@post
                }

                val slug = InputValidators.normalizeSlug(body.slug)
                if (slug == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "slug must be 2-64 lowercase letters, numbers, or hyphens"
                    )
                    return@post
                }

                if (!InputValidators.isValidContainerName(body.containerName)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "containerName contains invalid characters")
                    return@post
                }

                if (body.type.lowercase() !in listOf("frontend", "backend")) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "type must be 'frontend' or 'backend'")
                    return@post
                }

                val existing = ProjectRepository.findBySlug(slug, includeArchived = true)
                if (existing != null) {
                    call.respondError(HttpStatusCode.Conflict, "project_exists", "A project with this slug already exists")
                    return@post
                }

                val dueDate = InputValidators.parseDueDate(body.dueDate)
                if (body.dueDate?.isNotBlank() == true && dueDate == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "dueDate must be ISO format YYYY-MM-DD")
                    return@post
                }

                if (body.clientEmail?.isNotBlank() == true && !InputValidators.isValidEmail(body.clientEmail)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "clientEmail is not a valid email address")
                    return@post
                }

                val amountDue = body.amountDue?.let { BigDecimal.valueOf(it) }

                val project = ProjectRepository.create(
                    slug = slug,
                    name = body.name.trim(),
                    domain = body.domain.trim(),
                    containerName = body.containerName.trim(),
                    type = body.type.lowercase(),
                    clientName = body.clientName?.trim(),
                    clientEmail = body.clientEmail?.trim(),
                    amountDue = amountDue,
                    currency = body.currency,
                    dueDate = dueDate,
                    gracePeriodDays = body.gracePeriodDays
                )

                logger.info("Project created: $slug")
                call.respond(HttpStatusCode.Created, project.toResponse())
            }

            patch("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@patch
                }

                val body = try {
                    call.receive<UpdateProjectRequest>()
                } catch (e: Exception) {
                    logger.warn("Failed to parse update project request", e)
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@patch
                }

                if (body.type != null && !InputValidators.isValidProjectType(body.type)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "type must be 'frontend' or 'backend'")
                    return@patch
                }

                if (body.containerName != null && !InputValidators.isValidContainerName(body.containerName)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "containerName contains invalid characters")
                    return@patch
                }

                if (body.clientEmail?.isNotBlank() == true && !InputValidators.isValidEmail(body.clientEmail)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "clientEmail is not a valid email address")
                    return@patch
                }

                val dueDate = InputValidators.parseDueDate(body.dueDate)
                if (body.dueDate?.isNotBlank() == true && dueDate == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "dueDate must be ISO format YYYY-MM-DD")
                    return@patch
                }
                val amountDue = body.amountDue?.let { BigDecimal.valueOf(it) }

                val project = ProjectRepository.update(
                    slug = slug,
                    name = body.name?.trim(),
                    domain = body.domain?.trim(),
                    containerName = body.containerName?.trim(),
                    type = body.type?.lowercase(),
                    clientName = body.clientName?.trim(),
                    clientEmail = body.clientEmail?.trim(),
                    amountDue = amountDue,
                    currency = body.currency,
                    dueDate = dueDate,
                    gracePeriodDays = body.gracePeriodDays
                )

                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@patch
                }

                ProjectRepository.invalidateCache(slug)
                logger.info("Project updated: $slug")
                call.respond(project.toResponse())
            }

            delete("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@delete
                }

                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                val archived = ProjectRepository.archive(
                    slug = slug,
                    actor = actor,
                    reason = "Archived via admin API"
                )
                if (!archived) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@delete
                }

                logger.info("Project archived: $slug by $actor")
                call.respond(HttpStatusCode.NoContent)
            }

            post("/api/admin/projects/{slug}/block") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<StatusChangeRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A reason is required")
                    return@post
                }

                val reason = InputValidators.requireNonBlank(body.reason, "reason")
                if (reason == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A non-empty reason is required")
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                ProjectRepository.updateStatus(project.id, "manual_block", actor, reason)
                ProjectRepository.invalidateCache(slug)

                logger.info("Project blocked: $slug by $actor")
                call.respond(StatusChangeResponse(status = "blocked", slug = slug))
            }

            post("/api/admin/projects/{slug}/unblock") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<StatusChangeRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A reason is required")
                    return@post
                }

                val reason = InputValidators.requireNonBlank(body.reason, "reason")
                if (reason == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A non-empty reason is required")
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                ProjectRepository.updateStatus(project.id, "active", actor, reason)
                ProjectRepository.invalidateCache(slug)

                logger.info("Project unblocked: $slug by $actor")
                call.respond(StatusChangeResponse(status = "active", slug = slug))
            }

            post("/api/admin/projects/{slug}/payment/initialize") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<InitializePaymentRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Email is required")
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                if (project.amountDue == null || project.clientEmail == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Project has no amount_due or client_email configured"
                    )
                    return@post
                }

                val emailOverride = body.email.trim().ifBlank { null }
                if (emailOverride != null && !InputValidators.isValidEmail(emailOverride)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "email is not a valid email address")
                    return@post
                }

                val result = ProjectPaymentService.initializeForProject(
                    project = project,
                    emailOverride = emailOverride
                )

                result.fold(
                    onSuccess = { link ->
                        call.respond(InitializePaymentResponse(payment_link = link))
                    },
                    onFailure = { err ->
                        logger.error("Failed to initialize payment for $slug", err)
                        call.respondError(
                            HttpStatusCode.BadGateway,
                            "paystack_error",
                            err.message ?: "Failed to initialize payment with Paystack"
                        )
                    }
                )
            }

            get("/api/admin/audit") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val auditLog = AuditRepository.findAll(limit.coerceIn(1, 500)).map { it.toResponse() }
                call.respond(auditLog)
            }

            get("/api/admin/projects/{slug}/audit") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }
                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val auditLog = AuditRepository.findByProjectId(project.id).map { it.toResponse() }
                call.respond(auditLog)
            }

            // Docker container creation
            post("/api/admin/containers/create") {
                val body = try {
                    call.receive<CreateContainerRequest>()
                } catch (e: Exception) {
                    logger.warn("Failed to parse create container request", e)
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@post
                }

                if (body.name.isBlank() || !InputValidators.isValidContainerName(body.name)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A valid container name is required")
                    return@post
                }

                val parsedImage = parseImageRef(body.image)
                if (parsedImage == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "A valid image reference is required (e.g. 'nginx:latest' or 'nginx')"
                    )
                    return@post
                }

                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for container create (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }

                try {
                    // Stage 1: Image availability (optionally pull) before deeper validation.
                    val imageRef = parsedImage.normalized
                    if (!dockerService.imageExists(imageRef)) {
                        if (body.pullImage) {
                            dockerService.pullImage(parsedImage.repository, parsedImage.tag)
                        } else {
                            call.respondError(
                                HttpStatusCode.NotFound,
                                "image_not_found",
                                "Docker image '$imageRef' not found locally. Pull it first or set pullImage=true."
                            )
                            return@post
                        }
                    }

                    // Stage 2: Validate network selection.
                    val requestedNetwork = body.network.trim().ifBlank { "bridge" }
                    if (requestedNetwork == AppConfig.internalNetwork) {
                        dockerService.createNetworkIfMissing(requestedNetwork)
                    } else {
                        val existingNetworks = dockerService.listNetworks().map { it.name }.toSet()
                        if (requestedNetwork !in existingNetworks) {
                            call.respondError(
                                HttpStatusCode.BadRequest,
                                "network_not_found",
                                "Docker network '$requestedNetwork' not found. Use GET /api/admin/networks to populate the dropdown."
                            )
                            return@post
                        }
                    }

                    // Stage 3: Restart policy (optional).
                    val restartPolicy = body.restartPolicy?.trim()?.takeIf { it.isNotBlank() }
                    if (restartPolicy != null) {
                        val ok = restartPolicy in listOf("no", "always", "unless-stopped") ||
                            restartPolicy == "on-failure" ||
                            Regex("^on-failure:\\d{1,5}$").matches(restartPolicy)
                        if (!ok) {
                            call.respondError(
                                HttpStatusCode.BadRequest,
                                "invalid_request",
                                "restartPolicy must be one of: no, always, unless-stopped, on-failure, on-failure:<max-retries>"
                            )
                            return@post
                        }
                    }

                    // Stage 3: Ports (optional) and port conflicts.
                    val hostPorts = body.ports.keys.toSet()
                    val invalidPorts = hostPorts.filter { it !in 1..65535 } +
                        body.ports.values.filter { it !in 1..65535 }
                    if (invalidPorts.isNotEmpty()) {
                        call.respondError(
                            HttpStatusCode.BadRequest,
                            "invalid_request",
                            "Ports must be between 1 and 65535"
                        )
                        return@post
                    }

                    // Stage 4: Volume mounts (optional).
                    val invalidVolume = body.volumes.firstOrNull { it.hostPath.isBlank() || it.containerPath.isBlank() }
                    if (invalidVolume != null) {
                        call.respondError(
                            HttpStatusCode.BadRequest,
                            "invalid_request",
                            "volumes require both hostPath and containerPath"
                        )
                        return@post
                    }

                    if (hostPorts.isNotEmpty()) {
                        val inUse = dockerService.hostPortsInUse()
                        val conflicts = hostPorts.intersect(inUse).sorted()
                        if (conflicts.isNotEmpty()) {
                            call.respondError(
                                HttpStatusCode.Conflict,
                                "port_conflict",
                                "Port(s) already in use: ${conflicts.joinToString(", ")}"
                            )
                            return@post
                        }
                    }

                    val container = dockerService.createContainer(
                        body.copy(
                            image = imageRef,
                            network = requestedNetwork,
                            restartPolicy = restartPolicy
                        )
                    )
                    
                    logger.info("Container created via API: ${body.name}")
                    call.respond(HttpStatusCode.Created, CreateContainerResponse(
                        id = container.id,
                        name = container.name,
                        status = container.status,
                        ports = container.ports
                    ))
                } catch (e: Exception) {
                    logger.error("Failed to create container: ${body.name}", e)
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "docker_error",
                        e.message ?: "Failed to create container"
                    )
                } finally {
                    dockerService.close()
                }
            }

            // Wizard helpers: validate early stages before attempting full container creation.
            post("/api/admin/images/status") {
                val body = try {
                    call.receive<ImageStatusRequest>()
                } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }

                val parsed = parseImageRef(body.image)
                if (parsed == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "A valid image reference is required (e.g. 'nginx:latest' or 'nginx')"
                    )
                    return@post
                }

                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for image status (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }

                try {
                    val normalized = parsed.normalized
                    call.respond(ImageStatusResponse(image = normalized, exists = dockerService.imageExists(normalized)))
                } finally {
                    dockerService.close()
                }
            }

            post("/api/admin/containers/wizard/ports/check") {
                val body = try {
                    call.receive<PortsAvailabilityRequest>()
                } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }

                val ports = body.hostPorts.mapNotNull { it.takeIf { p -> p in 1..65535 } }.toSet()
                if (ports.size != body.hostPorts.size) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "hostPorts must be between 1 and 65535")
                    return@post
                }

                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for ports check (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }

                try {
                    val inUse = dockerService.hostPortsInUse()
                    val conflicts = ports.intersect(inUse).sorted()
                    call.respond(
                        PortsAvailabilityResponse(
                            ok = conflicts.isEmpty(),
                            conflicts = conflicts
                        )
                    )
                } finally {
                    dockerService.close()
                }
            }

            // Docker image deletion
            post("/api/admin/images/delete") {
                val body = try {
                    call.receive<DeleteImageRequest>()
                } catch (e: Exception) {
                    logger.warn("Failed to parse delete image request", e)
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@post
                }

                if (body.image.isBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "image is required")
                    return@post
                }

                try {
                    val dockerService = DockerService(AppConfig.dockerSocket)
                    dockerService.deleteImage(body.image, body.tag, body.force)
                    dockerService.close()
                    
                    logger.info("Image deleted via API: ${body.image}:${body.tag}")
                    call.respond(DeleteImageResponse(
                        status = "deleted",
                        image = "${body.image}:${body.tag}"
                    ))
                } catch (e: Exception) {
                    logger.error("Failed to delete image: ${body.image}:${body.tag}", e)
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "docker_error",
                        e.message ?: "Failed to delete image"
                    )
                }
            }

            // Docker container deletion
            post("/api/admin/containers/{name}/delete") {
                val name = call.parameters["name"]
                if (name == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_name", "Missing container name path parameter")
                    return@post
                }

                try {
                    val dockerService = DockerService(AppConfig.dockerSocket)
                    dockerService.deleteContainer(name)
                    dockerService.close()
                    
                    logger.info("Container deleted via API: $name")
                    call.respond(mapOf("status" to "deleted", "container" to name))
                } catch (e: Exception) {
                    logger.error("Failed to delete container: $name", e)
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "docker_error",
                        e.message ?: "Failed to delete container"
                    )
                }
            }
        }
    }
}
