package com.gatekeeper.admin

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.paystack.PaystackClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

private val logger = LoggerFactory.getLogger("com.gatekeeper.admin.ProjectAdminRoutes")
private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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
            // List all projects
            get("/api/admin/projects") {
                val projects = ProjectRepository.findAll()
                call.respond(projects)
            }

            // Get single project with payment history and audit log
            get("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing slug")
                )
                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
                    return@get
                }
                val payments = PaymentRepository.findByProjectId(project.id)
                val auditLog = AuditRepository.findByProjectId(project.id)
                call.respond(mapOf(
                    "project" to project,
                    "payments" to payments,
                    "audit_log" to auditLog
                ))
            }

            // Create project
            post("/api/admin/projects") {
                val body = try {
                    json.decodeFromString<CreateProjectRequest>(call.receiveText())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request: ${e.message}"))
                    return@post
                }

                if (body.slug.isBlank() || body.name.isBlank() || body.domain.isBlank() || body.containerName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "slug, name, domain, and containerName are required"))
                    return@post
                }

                if (body.type.lowercase() !in listOf("frontend", "backend")) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "type must be 'frontend' or 'backend'"))
                    return@post
                }

                // Check for duplicate slug
                val existing = ProjectRepository.findBySlug(body.slug)
                if (existing != null) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "project with this slug already exists"))
                    return@post
                }

                val dueDate = body.dueDate?.let { LocalDate.parse(it) }
                val amountDue = body.amountDue?.let { BigDecimal.valueOf(it) }

                val project = ProjectRepository.create(
                    slug = body.slug.lowercase().trim(),
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

                logger.info("Project created: ${body.slug}")
                call.respond(HttpStatusCode.Created, project)
            }

            // Update project
            patch("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"] ?: return@patch call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing slug")
                )

                val body = try {
                    json.decodeFromString<UpdateProjectRequest>(call.receiveText())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request: ${e.message}"))
                    return@patch
                }

                val dueDate = body.dueDate?.let { LocalDate.parse(it) }
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
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
                    return@patch
                }

                ProjectRepository.invalidateCache(slug)
                logger.info("Project updated: $slug")
                call.respond(project)
            }

            // Block project
            post("/api/admin/projects/{slug}/block") {
                val slug = call.parameters["slug"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing slug")
                )
                val body = try {
                    json.decodeFromString<StatusChangeRequest>(call.receiveText())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason required"))
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
                    return@post
                }

                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                ProjectRepository.updateStatus(project.id, "manual_block", actor, body.reason)
                ProjectRepository.invalidateCache(slug)

                logger.info("Project blocked: $slug by $actor")
                call.respond(mapOf("status" to "blocked", "slug" to slug))
            }

            // Unblock project
            post("/api/admin/projects/{slug}/unblock") {
                val slug = call.parameters["slug"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing slug")
                )
                val body = try {
                    json.decodeFromString<StatusChangeRequest>(call.receiveText())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason required"))
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
                    return@post
                }

                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                ProjectRepository.updateStatus(project.id, "active", actor, body.reason)
                ProjectRepository.invalidateCache(slug)

                logger.info("Project unblocked: $slug by $actor")
                call.respond(mapOf("status" to "active", "slug" to slug))
            }

            // Initialize payment link for a project
            post("/api/admin/projects/{slug}/payment/initialize") {
                val slug = call.parameters["slug"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing slug")
                )
                val body = try {
                    json.decodeFromString<InitializePaymentRequest>(call.receiveText())
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "email required"))
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
                    return@post
                }

                if (project.amountDue == null || project.clientEmail == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "project has no amount_due or client_email"))
                    return@post
                }

                val result = PaystackClient.initializePayment(
                    email = body.email.ifBlank { project.clientEmail!! },
                    amountNaira = project.amountDue,
                    projectSlug = project.slug
                )

                result.fold(
                    onSuccess = { link ->
                        call.respond(InitializePaymentResponse(payment_link = link))
                    },
                    onFailure = { err ->
                        logger.error("Failed to initialize payment for $slug", err)
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to (err.message ?: "paystack_error")))
                    }
                )
            }

            // Global audit log (most recent entries across all projects)
            get("/api/admin/audit") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val auditLog = AuditRepository.findAll(limit.coerceIn(1, 500))
                call.respond(auditLog)
            }

            // Get audit log for a project
            get("/api/admin/projects/{slug}/audit") {
                val slug = call.parameters["slug"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest, mapOf("error" to "missing slug")
                )
                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "project not found"))
                    return@get
                }
                val auditLog = AuditRepository.findByProjectId(project.id)
                call.respond(auditLog)
            }
        }
    }
}