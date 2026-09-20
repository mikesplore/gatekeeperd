package com.gatekeeper.admin

import com.gatekeeper.api.dto.AuditLogResponse
import com.gatekeeper.api.dto.AuditLogPageResponse
import com.gatekeeper.api.dto.PaymentResponse
import com.gatekeeper.api.dto.ProjectDetailResponse
import com.gatekeeper.api.dto.ProjectResponse
import com.gatekeeper.api.dto.ProjectsListResponse
import com.gatekeeper.api.dto.StatusChangeResponse
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.docker.ContainerCreatePlanResult
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.CreateContainerResponse
import com.gatekeeper.docker.ContainerWizardContextResponse
import com.gatekeeper.docker.ContainerWizardValidateResponse
import com.gatekeeper.docker.DockerWizardInspect
import com.gatekeeper.docker.computeContainerCreatePlan
import com.gatekeeper.docker.DeleteImageRequest
import com.gatekeeper.docker.DeleteImageResponse
import com.gatekeeper.docker.DockerService
import com.gatekeeper.docker.ImageStatusRequest
import com.gatekeeper.docker.ImageStatusResponse
import com.gatekeeper.docker.PortsAvailabilityRequest
import com.gatekeeper.docker.PortsAvailabilityResponse
import com.gatekeeper.docker.parseImageRef
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.extractConfiguredContainerName
import com.gatekeeper.paystack.ProjectPaymentService
import com.gatekeeper.payments.ProjectBalanceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.math.BigDecimal

private val logger = LoggerFactory.getLogger("com.gatekeeper.admin.ProjectAdminRoutes")
private val json = Json.Default

private class DockerServiceWizardInspect(private val dockerService: DockerService) : DockerWizardInspect {
    override fun imageExists(imageRef: String): Boolean = dockerService.imageExists(imageRef)
    override fun containerExists(name: String): Boolean = dockerService.getContainer(name) != null
    override fun listNetworkNames(): Set<String> = dockerService.listNetworks().map { it.name }.toSet()
    override fun hostPortsInUse(): Set<Int> = dockerService.hostPortsInUse()
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
    val gracePeriodDays: Int = 3,
    val deploymentMode: String = "developer_hosted",
    val serviceMode: String = "development",
    val lifecycleStatus: String = "active"
)

@Serializable
data class UpdateProjectRequest(
    // Accepted for backwards compatibility with response-shaped edit payloads; the path slug is authoritative.
    val slug: String? = null,
    val name: String? = null,
    val domain: String? = null,
    val containerName: String? = null,
    val type: String? = null,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val amountDue: Double? = null,
    val currency: String? = null,
    val dueDate: String? = null,
    val gracePeriodDays: Int? = null,
    val deploymentMode: String? = null,
    val serviceMode: String? = null,
    val lifecycleStatus: String? = null
)

@Serializable
data class CreateProjectAdjustmentRequest(
    val type: String,
    val amount: Double,
    val reason: String
)

@Serializable
data class ProjectAdjustmentResponse(
    val id: String,
    val projectId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val actor: String,
    val createdAt: String,
    val oldBalance: Double,
    val newBalance: Double
)

@Serializable
data class InitializePaymentRequest(val email: String)

@Serializable
data class InitializePaymentResponse(val payment_link: String)

@Serializable
data class StatusChangeRequest(val reason: String)

@Serializable
data class TransferProjectRequest(
    val deploymentMode: String = "client_hosted",
    val serviceMode: String = "production"
)

@Serializable
data class GithubDeploymentSourceRequest(
    val repository: String? = null,
    val gitRef: String = "main",
    val imageName: String? = null,
    val imageTag: String = "latest",
    val autoDeploy: Boolean = false
)

private val deploymentModes = setOf("developer_hosted", "client_hosted", "external_hosted")
private val serviceModes = setOf("development", "testing", "production")
private val lifecycleStatuses = setOf("active", "transferred", "archived", "cancelled")

@Serializable
data class ProjectWizardContainerOption(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val ports: String,
    val suggestedSlug: String? = null
)

@Serializable
data class ProjectCreateWizardContextResponse(
    val containers: List<ProjectWizardContainerOption>,
    val existingProjectSlugs: List<String>
)

fun Application.configureProjectAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            patch("/api/admin/projects/{slug}/deployment-source") {
                val slug = call.parameters["slug"] ?: run { call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug"); return@patch }
                val body = runCatching { call.receive<GithubDeploymentSourceRequest>() }.getOrNull() ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment source"); return@patch }
                if ((body.repository != null && !body.repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) || !body.gitRef.matches(Regex("^[A-Za-z0-9._/-]+$")) || (body.imageName != null && !body.imageName.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) || !body.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$"))) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_source", "Repository, ref, image name, or tag is invalid"); return@patch
                }
                val project = ProjectRepository.updateGithubDeployment(slug, body.repository, body.gitRef, body.imageName, body.imageTag, body.autoDeploy) ?: run { call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found"); return@patch }
                call.respond(project.toResponse())
            }
            get("/api/admin/projects") {
                val search = call.request.queryParameters["search"]?.trim()?.lowercase()
                val status = call.request.queryParameters["status"]
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 500
                val filteredProjects = ProjectRepository.findAll().asSequence()
                    .filter { search.isNullOrBlank() || listOf(it.name, it.slug, it.domain, it.clientName ?: "").any { value -> value.lowercase().contains(search) } }
                    .filter { status.isNullOrBlank() || it.status == status }
                    .toList()
                call.respond(
                    ProjectsListResponse(
                        projects = filteredProjects.drop(offset).take(limit).map { project ->
                            val remaining = ProjectBalanceService.outstandingBalance(project)
                            project.toResponse(remaining)
                        },
                        total = filteredProjects.size,
                        limit = limit,
                        offset = offset
                    )
                )
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
                val adjustments = ProjectAdjustmentRepository.findByProjectId(project.id)
                val auditLog = AuditRepository.findByProjectId(project.id)
                call.respond(
                    ProjectDetailResponse(
                        project = project.toResponse(
                            ProjectBalanceService.outstandingBalance(project)
                        ),
                        payments = payments.map { it.toResponse() },
                        audit_log = auditLog.map { it.toResponse() },
                        adjustments = adjustments.map { it.toResponse() }
                    )
                )
            }

            get("/api/admin/projects/{slug}/invoice") {
                val slug = call.parameters["slug"]
                val project = slug?.let { ProjectRepository.findBySlug(it) }
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val lookup = ScribedIntegrationClient.invoiceStatus(project.id.toString())
                if (lookup.status == HttpStatusCode.NotFound) {
                    call.respondError(HttpStatusCode.NotFound, "invoice_unavailable", "No Scribed invoice is available for this project")
                    return@get
                }
                if (lookup.body == null) {
                    logger.warn("Scribed invoice lookup failed for project ${project.id}: status=${lookup.status}, error=${lookup.error}")
                    call.respondError(HttpStatusCode.BadGateway, "invoice_integration_unavailable", "Scribed invoice lookup failed")
                    return@get
                }
                val scribedBase = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
                val invoice = lookup.body["invoice"]?.jsonObject
                val invoiceId = invoice?.get("id")?.jsonPrimitive?.longOrNull
                val response = if (invoiceId != null && scribedBase.isNotBlank() && invoice != null) {
                    val invoiceUrl = "$scribedBase/invoices/$invoiceId"
                    JsonObject(lookup.body + ("invoice" to JsonObject(invoice + ("download_url" to JsonPrimitive(invoiceUrl)))))
                } else lookup.body
                call.respond(response)
            }

            get("/api/admin/projects/{slug}/invoice/download") {
                val slug = call.parameters["slug"]
                val project = slug?.let { ProjectRepository.findBySlug(it) }
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val lookup = ScribedIntegrationClient.invoiceStatus(project.id.toString())
                val invoiceId = lookup.body?.get("invoice")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
                if (invoiceId == null) {
                    call.respondError(HttpStatusCode.NotFound, "invoice_unavailable", "No Scribed invoice is available for this project")
                    return@get
                }
                val (status, bytes) = ScribedIntegrationClient.invoicePdf(invoiceId)
                if (bytes == null) {
                    call.respondError(HttpStatusCode.BadGateway, "invoice_download_failed", "Unable to download invoice from Scribed")
                    return@get
                }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=invoice-${project.slug}.pdf")
                call.respondBytes(bytes, ContentType.Application.Pdf)
            }

            post("/api/admin/projects/{slug}/invoice/resync") {
                val slug = call.parameters["slug"]
                val project = slug?.let { ProjectRepository.findBySlug(it) }
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }
                ScribedIntegrationClient.notifyLedger(project, "resync-${java.util.UUID.randomUUID()}")
                call.respond(HttpStatusCode.Accepted, mapOf("status" to "queued", "project" to project.slug))
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

                if (!InputValidators.isValidContainerRef(body.containerName)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "containerName must be 'name' or 'name:port'")
                    return@post
                }

                if (body.type.lowercase() !in listOf("frontend", "backend")) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "type must be 'frontend' or 'backend'")
                    return@post
                }

                if (body.deploymentMode !in deploymentModes || body.serviceMode !in serviceModes || body.lifecycleStatus !in lifecycleStatuses) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment, service, or lifecycle mode")
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

                // Enforce container-first flow: the referenced Docker container must exist.
                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for project create validation (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }

                val containerRef = body.containerName.trim()
                val containerName = extractConfiguredContainerName(containerRef)
                if (containerName == null) {
                    dockerService.close()
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "containerName is invalid")
                    return@post
                }

                try {
                    if (dockerService.getContainer(containerName) == null) {
                        call.respondError(
                            HttpStatusCode.BadRequest,
                            "container_not_found",
                            "Docker container '$containerName' was not found. Create/start the container first, then create the project."
                        )
                        return@post
                    }
                } finally {
                    dockerService.close()
                }

                val project = ProjectRepository.create(
                    slug = slug,
                    name = body.name.trim(),
                    domain = body.domain.trim(),
                    containerName = containerRef,
                    type = body.type.lowercase(),
                    clientName = body.clientName?.trim(),
                    clientEmail = body.clientEmail?.trim(),
                    amountDue = amountDue,
                    currency = body.currency,
                    dueDate = dueDate,
                    gracePeriodDays = body.gracePeriodDays,
                    deploymentMode = body.deploymentMode,
                    serviceMode = body.serviceMode,
                    lifecycleStatus = body.lifecycleStatus
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

                val parsed = try {
                    val payload = call.receive<JsonObject>()
                    json.decodeFromJsonElement<UpdateProjectRequest>(payload) to payload.keys
                } catch (e: Exception) {
                    logger.warn("Failed to parse update project request", e)
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@patch
                }

                val (body, presentFields) = parsed

                if (body.type != null && !InputValidators.isValidProjectType(body.type)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "type must be 'frontend' or 'backend'")
                    return@patch
                }

                if (body.containerName != null && !InputValidators.isValidContainerRef(body.containerName)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "containerName must be 'name' or 'name:port'")
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

                if ((body.deploymentMode != null && body.deploymentMode !in deploymentModes) ||
                    (body.serviceMode != null && body.serviceMode !in serviceModes) ||
                    (body.lifecycleStatus != null && body.lifecycleStatus !in lifecycleStatuses)
                ) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment, service, or lifecycle mode")
                    return@patch
                }

                if (body.lifecycleStatus != null) {
                    val current = ProjectRepository.findBySlug(slug)
                    if (current == null) {
                        call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                        return@patch
                    }
                    if (body.lifecycleStatus != current.lifecycleStatus) {
                        call.respondError(
                            HttpStatusCode.Conflict,
                            "invalid_lifecycle_transition",
                            "Use the dedicated transfer or archive operation for lifecycle changes"
                        )
                        return@patch
                    }
                }

                // If containerName is updated, enforce that the referenced Docker container exists.
                if (body.containerName != null) {
                    val dockerService = try {
                        DockerService(AppConfig.dockerSocket)
                    } catch (e: Exception) {
                        logger.warn("Docker not available for project update validation (non-fatal): ${e.message}")
                        call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                        return@patch
                    }

                    val containerRef = body.containerName.trim()
                    val containerName = extractConfiguredContainerName(containerRef)
                    if (containerName == null) {
                        dockerService.close()
                        call.respondError(HttpStatusCode.BadRequest, "invalid_request", "containerName is invalid")
                        return@patch
                    }

                    try {
                        if (dockerService.getContainer(containerName) == null) {
                            call.respondError(
                                HttpStatusCode.BadRequest,
                                "container_not_found",
                                "Docker container '$containerName' was not found. Create/start the container first, then update the project."
                            )
                            return@patch
                        }
                    } finally {
                        dockerService.close()
                    }
                }

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
                    gracePeriodDays = body.gracePeriodDays,
                    clearClientName = "clientName" in presentFields && body.clientName == null,
                    clearClientEmail = "clientEmail" in presentFields && body.clientEmail == null,
                    clearAmountDue = "amountDue" in presentFields && body.amountDue == null,
                    clearDueDate = "dueDate" in presentFields && body.dueDate == null,
                    deploymentMode = body.deploymentMode,
                    serviceMode = body.serviceMode,
                    lifecycleStatus = body.lifecycleStatus
                )

                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@patch
                }

                ProjectRepository.invalidateCache(slug)
                logger.info("Project updated: $slug")
                call.respond(project.toResponse())
            }

            post("/api/admin/projects/{slug}/adjustments") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = runCatching { call.receive<CreateProjectAdjustmentRequest>() }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid adjustment request")
                    return@post
                }
                val type = runCatching { AdjustmentType.valueOf(body.type.trim().uppercase()) }.getOrNull()
                if (type == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_type", "type must be ADDITIONAL_CHARGE or DISCOUNT")
                    return@post
                }
                val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
                if (amount == null || amount <= BigDecimal.ZERO) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_amount", "amount must be greater than zero")
                    return@post
                }
                if (body.reason.isBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_reason", "reason is required")
                    return@post
                }
                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }
                val oldBalance = ProjectBalanceService.outstandingBalance(project)
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                val adjustment = try {
                    ProjectAdjustmentRepository.create(project.id, type, amount, body.reason, actor)
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment", e.message ?: "Invalid adjustment")
                    return@post
                }
                val newBalance = ProjectBalanceService.outstandingBalance(project)
                AuditRepository.write(
                    project.id,
                    "project_adjustment",
                    actor,
                    "${type.name} amount=$amount oldBalance=$oldBalance newBalance=$newBalance reason=${body.reason.trim()}"
                )
                ScribedIntegrationClient.notifyLedger(project, "adjustment-${adjustment.id}")
                ProjectRepository.invalidateCache(slug)
                call.respond(ProjectAdjustmentResponse(adjustment.id.toString(), project.id.toString(), type.name, amount.toDouble(), adjustment.reason, actor, adjustment.createdAt.toString(), oldBalance.toDouble(), newBalance.toDouble()))
            }

            post("/api/admin/projects/{slug}/transfer") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<TransferProjectRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid transfer request")
                    return@post
                }
                if (body.deploymentMode !in deploymentModes || body.serviceMode !in serviceModes) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment or service mode")
                    return@post
                }
                val current = ProjectRepository.findBySlug(slug)
                if (current == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }
                if (current.lifecycleStatus !in setOf("active", "transferred")) {
                    call.respondError(HttpStatusCode.Conflict, "invalid_lifecycle_transition", "Only active or transferred projects can be transferred")
                    return@post
                }
                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"
                val updated = ProjectRepository.transfer(
                    slug = slug,
                    deploymentMode = body.deploymentMode,
                    serviceMode = body.serviceMode,
                    actor = actor,
                    reason = "Transferred via admin API"
                ) ?: current
                call.respond(updated.toResponse())
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

                runCatching {
                    val nginx = NginxService()
                    val removed = nginx.removeProject(slug)
                    if (!removed) {
                        logger.warn("Project archived but nginx cleanup failed for slug=$slug (removeProject returned false)")
                        return@runCatching
                    }
                    val reloaded = nginx.reloadNginx()
                    if (!reloaded) {
                        logger.warn("Project archived but nginx cleanup reload failed for slug=$slug")
                    }
                }.onFailure { e ->
                    logger.warn("Project archived but nginx cleanup errored for slug=$slug: ${e.message}")
                }

                logger.info("Project archived: $slug by $actor")
                call.respond(HttpStatusCode.NoContent)
            }

            // Wizard helpers: drive a container-first deployment flow (container -> project -> nginx).
            get("/api/admin/projects/wizard/context") {
                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for project wizard context (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }

                try {
                    val containers = dockerService.listContainers(all = true)
                        .sortedBy { it.name.lowercase() }
                        .map { c ->
                            val suggested = InputValidators.normalizeSlug(
                                c.name.lowercase()
                                    .replace(Regex("[^a-z0-9-]"), "-")
                                    .replace(Regex("-{2,}"), "-")
                                    .trim('-')
                            )
                            ProjectWizardContainerOption(
                                id = c.id,
                                name = c.name,
                                image = c.image,
                                state = c.state,
                                ports = c.ports,
                                suggestedSlug = suggested
                            )
                        }

                    val existingSlugs = ProjectRepository.findAll(includeArchived = true).map { it.slug }.sorted()
                    call.respond(ProjectCreateWizardContextResponse(containers = containers, existingProjectSlugs = existingSlugs))
                } finally {
                    dockerService.close()
                }
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

                ProjectRepository.updateStatus(project.id, "manual_block", actor, reason, blockReason = "manual")
                ProjectRepository.invalidateCache(slug)
                ScribedIntegrationClient.notifySuspension(project.copy(status = "manual_block", blockReason = "manual"), "manual_block: $reason")

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
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val query = call.request.queryParameters["q"]
                val action = call.request.queryParameters["action"]
                val total = AuditRepository.count(query, action)
                val entries = AuditRepository.findPage(limit, offset, query, action).map { it.toResponse() }
                call.respond(AuditLogPageResponse(entries, total, limit, offset, offset + entries.size < total))
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

                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for container create (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }

                try {
                    val inspect = DockerServiceWizardInspect(dockerService)
                    when (val planResult = computeContainerCreatePlan(
                        request = body,
                        internalNetwork = AppConfig.internalNetwork,
                        docker = inspect
                    )) {
                        is ContainerCreatePlanResult.Err -> {
                            if (planResult.data != null) {
                                call.respondErrorWithData(planResult.status, planResult.code, planResult.message, planResult.data)
                            } else {
                                call.respondError(planResult.status, planResult.code, planResult.message)
                            }
                            return@post
                        }

                        is ContainerCreatePlanResult.Ok -> {
                            val plan = planResult.plan

                            if (plan.willPullImage) {
                                val useCliPull = plan.normalizedRequest.pullViaCli || AppConfig.dockerPullViaCli
                                if (useCliPull) {
                                    dockerService.pullImageViaCli(plan.normalizedRequest.image, AppConfig.dockerSocket)
                                } else {
                                    dockerService.pullImage(plan.parsedImage.repository, plan.parsedImage.tag)
                                }
                            }
                            if (plan.willCreateInternalNetworkIfMissing) {
                                dockerService.createNetworkIfMissing(AppConfig.internalNetwork)
                            }

                            val container = dockerService.createContainer(plan.normalizedRequest)
                            logger.info("Container created via API: ${plan.normalizedRequest.name}")
                            call.respond(
                                HttpStatusCode.Created,
                                CreateContainerResponse(
                                    id = container.id,
                                    name = container.name,
                                    status = container.status,
                                    ports = container.ports
                                )
                            )
                        }
                    }
                    
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
            get("/api/admin/containers/wizard/context") {
                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for wizard context (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@get
                }

                try {
                    val networks = dockerService.listNetworks().map { it.name }.sorted()
                    val internal = AppConfig.internalNetwork
                    call.respond(
                        ContainerWizardContextResponse(
                            internalNetwork = internal,
                            internalNetworkExists = internal in networks,
                            networks = networks
                        )
                    )
                } finally {
                    dockerService.close()
                }
            }

            post("/api/admin/containers/wizard/validate") {
                val body = try {
                    call.receive<CreateContainerRequest>()
                } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }

                val dockerService = try {
                    DockerService(AppConfig.dockerSocket)
                } catch (e: Exception) {
                    logger.warn("Docker not available for wizard validate (non-fatal): ${e.message}")
                    call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is not available")
                    return@post
                }

                try {
                    val inspect = DockerServiceWizardInspect(dockerService)
                    when (val planResult = computeContainerCreatePlan(
                        request = body,
                        internalNetwork = AppConfig.internalNetwork,
                        docker = inspect
                    )) {
                        is ContainerCreatePlanResult.Err -> {
                            if (planResult.data != null) {
                                call.respondErrorWithData(planResult.status, planResult.code, planResult.message, planResult.data)
                            } else {
                                call.respondError(planResult.status, planResult.code, planResult.message)
                            }
                        }

                        is ContainerCreatePlanResult.Ok -> {
                            val plan = planResult.plan
                            call.respond(
                                ContainerWizardValidateResponse(
                                    success = true,
                                    message = "Validated successfully (no changes applied)",
                                    normalizedRequest = plan.normalizedRequest,
                                    imageExists = plan.imageExists,
                                    willPullImage = plan.willPullImage,
                                    networkExists = plan.networkExists,
                                    willCreateInternalNetworkIfMissing = plan.willCreateInternalNetworkIfMissing,
                                    warnings = plan.warnings
                                )
                            )
                        }
                    }
                } finally {
                    dockerService.close()
                }
            }

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
