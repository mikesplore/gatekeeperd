package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentEventRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.docker.DockerService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.config.AppConfig
import com.gatekeeper.paystack.replayPaystackWebhook
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import com.gatekeeper.plugins.Metrics
import java.time.OffsetDateTime

@Serializable
data class ProjectHealthResponse(
    val project: com.gatekeeper.api.dto.ProjectResponse,
    val container: String? = null,
    val containerHealth: String? = null,
    val nginxEnabled: Boolean,
    val certificateInstalled: Boolean,
    val readiness: String
)

@Serializable
data class BulkProjectRequest(val slugs: List<String>, val reason: String)

@Serializable
data class BulkProjectResult(val slug: String, val status: String, val message: String? = null)

@Serializable data class DashboardSummaryResponse(
    val generatedAt: String,
    val projects: Map<String, Long>,
    val payments: Map<String, Long>,
    val revenue: Map<String, String>,
    val integrations: Map<String, Long>,
    val nginx: Map<String, Long>,
    val metrics: Map<String, Long>
)

fun Application.configureOperationsAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/dashboard/summary") {
                val projects = ProjectRepository.findAll()
                val payments = PaymentRepository.findAllFiltered(null, null, null, null, 10000, 0).first.map { it.payment }
                val revenue = PaymentRepository.revenueTotals()
                val outbox = IntegrationOutboxRepository.summary()
                val available = java.io.File(AppConfig.nginxSitesAvailablePath).listFiles()?.count { it.isFile && !it.name.startsWith(".") }?.toLong() ?: 0
                val enabled = java.io.File(AppConfig.nginxSitesEnabledPath).listFiles()?.size?.toLong() ?: 0
                call.respond(DashboardSummaryResponse(OffsetDateTime.now().toString(), projects.groupingBy { it.status.lowercase() }.eachCount().mapValues { it.value.toLong() }, payments.groupingBy { it.gatewayStatus.lowercase() }.eachCount().mapValues { it.value.toLong() }, mapOf("thisMonth" to revenue.first.toPlainString(), "lastMonth" to revenue.second.toPlainString()), mapOf("outboxPending" to outbox.pending, "outboxProcessing" to outbox.processing, "outboxDeadLetter" to outbox.deadLetter, "outboxDelivered" to outbox.delivered), mapOf("availableSites" to available, "enabledSites" to enabled), Metrics.snapshot()))
            }
            get("/api/admin/projects/{slug}/health") {
                val slug = call.parameters["slug"] ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }
                val project = ProjectRepository.findBySlug(slug) ?: run {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val docker = runCatching { DockerService(AppConfig.dockerSocket) }.getOrNull()
                val containerName = project.containerName.substringBefore(":")
                val containerHealth = docker?.let { service ->
                    try { service.containerHealth(containerName) } finally { service.close() }
                }
                val nginx = NginxService()
                val nginxEnabled = java.io.File("${AppConfig.nginxSitesAvailablePath}/$slug").exists() &&
                    java.io.File("${AppConfig.nginxSitesEnabledPath}/$slug").exists()
                val certificateInstalled = nginx.isCertificateInstalled(project.domain)
                val ready = project.status == "active" && (containerHealth == null || containerHealth == "running")
                call.respond(
                    ProjectHealthResponse(
                        project = project.toResponse(),
                        container = containerName,
                        containerHealth = containerHealth,
                        nginxEnabled = nginxEnabled,
                        certificateInstalled = certificateInstalled,
                        readiness = if (ready) "healthy" else "attention_required"
                    )
                )
            }

            post("/api/admin/projects/bulk/block") {
                val body = runCatching { call.receive<BulkProjectRequest>() }.getOrNull() ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "slugs and reason are required")
                    return@post
                }
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                call.respond(body.slugs.distinct().map { slug ->
                    val project = ProjectRepository.findBySlug(slug)
                    if (project == null) BulkProjectResult(slug, "failed", "Project not found")
                    else {
                        ProjectRepository.updateStatus(project.id, "manual_block", actor, body.reason, "manual")
                        BulkProjectResult(slug, "blocked")
                    }
                })
            }

            post("/api/admin/projects/bulk/unblock") {
                val body = runCatching { call.receive<BulkProjectRequest>() }.getOrNull() ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "slugs and reason are required")
                    return@post
                }
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                call.respond(body.slugs.distinct().map { slug ->
                    val project = ProjectRepository.findBySlug(slug)
                    if (project == null) BulkProjectResult(slug, "failed", "Project not found")
                    else {
                        ProjectRepository.updateStatus(project.id, "active", actor, body.reason)
                        BulkProjectResult(slug, "active")
                    }
                })
            }

            post("/api/admin/payment-events/{id}/replay") {
                val id = call.parameters["id"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() } ?: run {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_event_id", "Invalid payment event ID")
                    return@post
                }
                val event = PaymentEventRepository.findForReplay(id) ?: run {
                    call.respondError(HttpStatusCode.NotFound, "payment_event_not_found", "Payment event not found")
                    return@post
                }
                if (event.processingStatus != "failed") {
                    call.respondError(HttpStatusCode.Conflict, "event_not_failed", "Only failed payment events can be replayed")
                    return@post
                }
                val replayed = replayPaystackWebhook(event.rawPayload)
                if (!replayed) {
                    call.respondError(HttpStatusCode.UnprocessableEntity, "replay_failed", "Payment event replay failed integrity or processing checks")
                    return@post
                }
                PaymentEventRepository.markProcessed(id)
                call.respond(mapOf("status" to "replayed", "eventId" to id.toString()))
            }

            get("/api/admin/audit/export") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 1000
                val action = call.request.queryParameters["action"]
                val actor = call.request.queryParameters["actor"]
                val rows = AuditRepository.findFiltered(action, actor, limit)
                val csv = buildString {
                    appendLine("id,project_id,action,actor,reason,created_at")
                    rows.forEach { row ->
                        appendLine(listOf(row.id, row.projectId ?: "", row.action, row.actor, row.reason ?: "", row.createdAt).joinToString(",") { csvEscape(it.toString()) })
                    }
                }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=gatekeeper-audit.csv")
                call.respondText(csv, ContentType.Text.CSV)
            }
        }
    }
}

private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
