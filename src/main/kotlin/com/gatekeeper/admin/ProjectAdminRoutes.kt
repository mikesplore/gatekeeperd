package com.gatekeeper.admin

import com.gatekeeper.api.dto.AuditLogResponse
import com.gatekeeper.api.dto.PaymentResponse
import com.gatekeeper.api.dto.ProjectDetailResponse
import com.gatekeeper.api.dto.ProjectResponse
import com.gatekeeper.api.dto.StatusChangeResponse
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
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
    val currency: String = "NGN",
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

                val existing = ProjectRepository.findBySlug(slug)
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

                val deleted = ProjectRepository.delete(slug)
                if (!deleted) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@delete
                }

                logger.info("Project deleted: $slug")
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
        }
    }
}
