package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.api.dto.PaginatedResponse
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.NotificationRepository
import com.gatekeeper.db.repositories.PaymentEventRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.repositories.CustomerRepository
import com.gatekeeper.db.repositories.CertificateRepository
import com.gatekeeper.docker.DockerService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.NginxSiteRenderModel
import com.gatekeeper.nginx.requireCertificatePath
import com.gatekeeper.nginx.requireValidHostname
import com.gatekeeper.db.tables.ReconciliationStatus
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
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import java.io.File

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
    val metrics: Map<String, Long>,
    val certificateAlerts: List<String> = emptyList()
)

@Serializable data class DashboardSiteResponse(
    val slug: String, val projectId: String, val customerId: String? = null, val customerName: String? = null,
    val domain: String, val status: String, val dockerState: String? = null,
    val lastNginxError: String? = null, val lastDockerError: String? = null,
    val configVersion: Int, val available: Boolean, val enabled: Boolean
)

@Serializable data class DashboardSiteDetailResponse(
    val site: DashboardSiteResponse, val generatedConfig: String? = null, val currentConfig: String? = null,
    val certificateInstalled: Boolean? = null, val certificateDaysRemaining: Long? = null,
    val backups: List<String> = emptyList()
)

@Serializable data class DashboardCustomerResponse(
    val id: String, val name: String, val contactEmail: String? = null, val contactPhone: String? = null,
    val billingStatus: String, val siteCount: Int, val health: Map<String, Int>,
    val totalBilled: Double = 0.0, val totalPaid: Double = 0.0, val balance: Double = 0.0,
    val projects: List<DashboardCustomerProjectResponse> = emptyList()
)

@Serializable data class DashboardCustomerProjectResponse(
    val id: String, val slug: String, val name: String, val domain: String,
    val amountDue: Double? = null, val totalPaid: Double = 0.0, val balance: Double = 0.0,
    val status: String
)

@Serializable data class DashboardCustomerTransactionResponse(
    val id: String, val projectId: String, val projectName: String, val projectSlug: String,
    val amount: Double, val status: String, val gatewayStatus: String,
    val provider: String, val providerReference: String, val paidAt: String? = null, val createdAt: String
)

@Serializable data class DashboardSiteUpdateRequest(
    val domain: String? = null, val upstreamHost: String? = null, val upstreamMode: String? = null,
    val upstreamContainerName: String? = null, val upstreamExplicitPort: Int? = null,
    val tlsMode: String? = null, val certMode: String? = null, val certExplicitPath: String? = null,
    val gateEnabled: Boolean? = null, val bypassPaths: List<String>? = null
)

@Serializable data class DashboardCustomerCreateRequest(
    val name: String, val contactEmail: String? = null, val contactPhone: String? = null,
    val billingStatus: String = "unknown"
)

@Serializable data class DashboardConfirmationRequest(val confirm: Boolean = false)

private fun dashboardSite(site: SiteRepository.SiteRecord): DashboardSiteResponse {
    val project = ProjectRepository.findById(site.projectId)
    val customer = project?.customerId?.let(CustomerRepository::findById)
    val slug = site.projectSlug ?: site.projectId.toString()
    return DashboardSiteResponse(
        slug, site.projectId.toString(), customer?.id?.toString(), customer?.name, site.domain,
        site.reconciliationStatus.value, site.lastDockerError?.let { "down" } ?: "unknown",
        site.lastNginxError, site.lastDockerError, site.configVersion,
        java.io.File(AppConfig.nginxSitesAvailablePath, slug).isFile,
        java.nio.file.Files.isSymbolicLink(java.io.File(AppConfig.nginxSitesEnabledPath, slug).toPath())
    )
}

@Serializable data class NotificationResponse(
    val id: String,
    val title: String,
    val message: String,
    val severity: String,
    val action: String,
    val createdAt: String,
    val read: Boolean = false
)

fun Application.configureOperationsAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/dashboard/sites") {
                val status = call.request.queryParameters["status"]?.lowercase()
                call.respond(SiteRepository.findAll().filter { status == null || it.reconciliationStatus.value == status }.map(::dashboardSite))
            }
            get("/api/admin/dashboard/sites/{slug}") {
                val slug = call.parameters["slug"] ?: run { call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug"); return@get }
                val site = SiteRepository.findByProjectSlug(slug) ?: run { call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found"); return@get }
                val nginx = NginxService()
                val inspection = nginx.inspectSite(slug)
                val cert = nginx.resolveCertificateForDomain(site.domain)
                val expiry = cert?.let { nginx.certificateExpiry(it.certificateDomain) }
                call.respond(DashboardSiteDetailResponse(dashboardSite(site), inspection.content?.takeIf { inspection.managed }, inspection.content, cert != null, expiry?.second, nginx.listBackups(slug).map { it.name }))
            }
            patch("/api/admin/dashboard/sites/{slug}") {
                val slug = call.parameters["slug"] ?: run { call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug"); return@patch }
                val site = SiteRepository.findByProjectSlug(slug) ?: run { call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found"); return@patch }
                val body = runCatching { call.receive<DashboardSiteUpdateRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid site update body"); return@patch }
                val update = runCatching {
                    SiteRepository.SiteDashboardUpdate(
                        domain = body.domain?.let { requireValidHostname(it) }, upstreamHost = body.upstreamHost,
                        upstreamMode = body.upstreamMode?.let { com.gatekeeper.db.tables.UpstreamMode.valueOf(it.uppercase()) },
                        upstreamContainerName = body.upstreamContainerName, upstreamExplicitPort = body.upstreamExplicitPort?.also { require(it in 1..65535) },
                        tlsMode = body.tlsMode?.let { com.gatekeeper.db.tables.TlsMode.valueOf(it.uppercase()) },
                        certMode = body.certMode?.let { com.gatekeeper.db.tables.CertMode.valueOf(it.uppercase()) },
                        certExplicitPath = body.certExplicitPath?.let { requireCertificatePath(it, AppConfig.nginxSslCertPath) },
                        gateEnabled = body.gateEnabled, bypassPaths = body.bypassPaths
                    )
                }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_site_update", it.message ?: "Invalid site update"); return@patch }
                val updated = SiteRepository.updateDashboard(site.id, update) ?: run { call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found"); return@patch }
                val nginx = NginxService()
                val port = updated.upstreamExplicitPort ?: run { call.respondError(HttpStatusCode.UnprocessableEntity, "site_configuration_invalid", "Docker discovery updates require a live container"); return@patch }
                val cert = if (updated.certMode == com.gatekeeper.db.tables.CertMode.AUTO_RESOLVE) nginx.resolveCertificateForDomain(updated.domain) else null
                val config = nginx.generateNginxConfig(NginxSiteRenderModel(
                    slug = slug, domain = updated.domain, upstreamHost = updated.upstreamHost, appPort = port,
                    upstreamScheme = if (updated.tlsMode == com.gatekeeper.db.tables.TlsMode.HTTP_ONLY) "http" else "https",
                    tlsMode = when (updated.tlsMode) {
                        com.gatekeeper.db.tables.TlsMode.HTTP_ONLY -> com.gatekeeper.nginx.TlsRenderMode.HTTP_ONLY
                        com.gatekeeper.db.tables.TlsMode.HTTPS -> com.gatekeeper.nginx.TlsRenderMode.HTTPS
                        com.gatekeeper.db.tables.TlsMode.HTTPS_HTTP2 -> com.gatekeeper.nginx.TlsRenderMode.HTTPS_HTTP2
                    }, certificatePath = cert?.certificatePath ?: updated.certExplicitPath, certificateKeyPath = cert?.privateKeyPath,
                    upstreamMode = updated.upstreamMode, upstreamContainerName = updated.upstreamContainerName,
                    certMode = updated.certMode,
                    gateEnabled = updated.gateEnabled, bypassPaths = updated.bypassPaths
                ))
                if (!nginx.enableProject(slug, config)) { call.respondError(HttpStatusCode.UnprocessableEntity, "nginx_error", "Validation or activation failed; previous configuration was preserved"); return@patch }
                call.respond(dashboardSite(SiteRepository.findByProjectSlug(slug) ?: updated))
            }
            delete("/api/admin/dashboard/sites/{slug}") {
                val slug = call.parameters["slug"] ?: run { call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug"); return@delete }
                val project = ProjectRepository.findBySlug(slug) ?: run { call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found"); return@delete }
                val nginx = NginxService()
                if (!nginx.removeProject(slug)) { call.respondError(HttpStatusCode.InternalServerError, "nginx_cleanup_failed", "Unable to remove nginx artifacts"); return@delete }
                SiteRepository.deleteByProjectId(project.id)
                call.respond(mapOf("deleted" to true, "slug" to slug))
            }
            get("/api/admin/dashboard/dead-configs") {
                call.respond(SiteRepository.findAll().filter { it.reconciliationStatus == ReconciliationStatus.DEAD_CONFIG }.map(::dashboardSite))
            }
            delete("/api/admin/dashboard/dead-configs/{filename}") {
                val filename = call.parameters["filename"] ?: run { call.respondError(HttpStatusCode.BadRequest, "missing_filename", "Missing filename"); return@delete }
                if (filename != File(filename).name || !filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) { call.respondError(HttpStatusCode.BadRequest, "invalid_filename", "Invalid config filename"); return@delete }
                val body = runCatching { call.receive<DashboardConfirmationRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "confirmation_required", "Request body must contain confirm=true"); return@delete }
                if (!body.confirm) { call.respondError(HttpStatusCode.Conflict, "confirmation_required", "Set confirm=true to move this dead config to backup"); return@delete }
                val source = File(AppConfig.nginxSitesAvailablePath, filename)
                if (!source.isFile) { call.respondError(HttpStatusCode.NotFound, "config_not_found", "Dead config not found"); return@delete }
                val backup = File(source.parentFile, "$filename.bak-${System.currentTimeMillis()}")
                java.nio.file.Files.move(source.toPath(), backup.toPath())
                call.respond(mapOf("backedUp" to true, "backup" to backup.name))
            }
            post("/api/admin/dashboard/customers") {
                val body = runCatching { call.receive<DashboardCustomerCreateRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid customer body"); return@post }
                if (body.name.isBlank()) { call.respondError(HttpStatusCode.BadRequest, "invalid_customer", "Customer name is required"); return@post }
                val customer = CustomerRepository.create(body.name.trim(), body.contactEmail?.trim(), body.contactPhone?.trim(), body.billingStatus)
                call.respond(HttpStatusCode.Created, DashboardCustomerResponse(customer.id.toString(), customer.name, customer.contactEmail, customer.contactPhone, customer.billingStatus, 0, emptyMap()))
            }
            patch("/api/admin/dashboard/projects/{id}") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Invalid project ID"); return@patch }
                val body = runCatching { call.receive<Map<String, String?>>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid project body"); return@patch }
                val customerId = body["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (body.containsKey("customerId") && customerId == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_customer_id", "Invalid customer ID"); return@patch }
                if (customerId != null && CustomerRepository.findById(customerId) == null) { call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@patch }
                val project = ProjectRepository.assignCustomer(id, customerId) ?: run { call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found"); return@patch }
                call.respond(project)
            }
            get("/api/admin/dashboard/customers") {
                call.respond(CustomerRepository.findAll().map { customer ->
                    val owned = CustomerRepository.findSites(customer.id)
                    val sites = owned.mapNotNull { it.site }
                    val billed = owned.sumOf { it.project.amountDue ?: java.math.BigDecimal.ZERO }
                    val paid = owned.sumOf { PaymentRepository.successfulAmountForProject(it.project.id) }
                    DashboardCustomerResponse(customer.id.toString(), customer.name, customer.contactEmail, customer.contactPhone, customer.billingStatus, sites.size, sites.groupingBy { it.reconciliationStatus.value }.eachCount(), billed.toDouble(), paid.toDouble(), (billed - paid).max(java.math.BigDecimal.ZERO).toDouble())
                })
            }
            get("/api/admin/dashboard/customers/{id}") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_customer_id", "Invalid customer ID"); return@get }
                val customer = CustomerRepository.findById(id) ?: run { call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@get }
                val owned = CustomerRepository.findSites(id)
                val sites = owned.mapNotNull { it.site }.map(::dashboardSite)
                val projects = owned.map { ownedProject ->
                    val project = ownedProject.project
                    val paid = PaymentRepository.successfulAmountForProject(project.id)
                    DashboardCustomerProjectResponse(
                        id = project.id.toString(), slug = project.slug, name = project.name, domain = project.domain,
                        amountDue = project.amountDue?.toDouble(), totalPaid = paid.toDouble(),
                        balance = ((project.amountDue ?: java.math.BigDecimal.ZERO) - paid).max(java.math.BigDecimal.ZERO).toDouble(),
                        status = project.status
                    )
                }
                call.respond(DashboardCustomerResponse(
                    customer.id.toString(), customer.name, customer.contactEmail, customer.contactPhone, customer.billingStatus,
                    sites.size, sites.groupingBy { it.status }.eachCount(),
                    totalBilled = projects.sumOf { it.amountDue ?: 0.0 }, totalPaid = projects.sumOf { it.totalPaid },
                    balance = projects.sumOf { it.balance }, projects = projects
                ))
            }
            get("/api/admin/dashboard/customers/{id}/transactions") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_customer_id", "Invalid customer ID"); return@get }
                if (CustomerRepository.findById(id) == null) { call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@get }
                val transactions = CustomerRepository.findSites(id).flatMap { owned ->
                    PaymentRepository.findByProjectId(owned.project.id).map { payment ->
                        DashboardCustomerTransactionResponse(payment.id.toString(), owned.project.id.toString(), owned.project.name, owned.project.slug, payment.amount.toDouble(), payment.status, payment.gatewayStatus, payment.provider.name, payment.providerReference, payment.paidAt?.toString(), payment.createdAt.toString())
                    }
                }.sortedByDescending { it.createdAt }
                call.respond(transactions)
            }
            get("/api/admin/dashboard/summary") {
                val projects = ProjectRepository.findAll()
                val payments = PaymentRepository.findAllFiltered(null, null, null, null, 10000, 0).first.map { it.payment }
                val revenue = PaymentRepository.revenueTotals()
                val outbox = IntegrationOutboxRepository.summary()
                val available = java.io.File(AppConfig.nginxSitesAvailablePath).listFiles()?.count { it.isFile && !it.name.startsWith(".") }?.toLong() ?: 0
                val enabled = java.io.File(AppConfig.nginxSitesEnabledPath).listFiles()?.size?.toLong() ?: 0
                val siteCounts = SiteRepository.findAll().groupingBy { it.reconciliationStatus.value }.eachCount().mapValues { it.value.toLong() }
                val now = LocalDateTime.now()
                val certificateAlerts = CertificateRepository.findAll().flatMap { certificate ->
                    buildList {
                        certificate.lastRenewalError?.let { add("certificate for ${certificate.domain} renewal failed: $it") }
                        certificate.expiresAt?.let { expires ->
                            val days = java.time.Duration.between(now, expires).toDays()
                            if (days <= 7) add("certificate for ${certificate.domain} expires in ${days.coerceAtLeast(0)} days")
                        }
                    }
                }
                call.respond(DashboardSummaryResponse(OffsetDateTime.now().toString(), projects.groupingBy { it.status.lowercase() }.eachCount().mapValues { it.value.toLong() }, payments.groupingBy { it.gatewayStatus.lowercase() }.eachCount().mapValues { it.value.toLong() }, mapOf("thisMonth" to revenue.first.toPlainString(), "lastMonth" to revenue.second.toPlainString()), mapOf("outboxPending" to outbox.pending, "outboxProcessing" to outbox.processing, "outboxDeadLetter" to outbox.deadLetter, "outboxDelivered" to outbox.delivered), siteCounts + mapOf("availableSites" to available, "enabledSites" to enabled), Metrics.snapshot(), certificateAlerts))
            }
            get("/api/admin/notifications") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val principal = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                val projectId = call.request.queryParameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val severity = call.request.queryParameters["severity"]; val action = call.request.queryParameters["type"]; val from = call.request.queryParameters["from"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }; val to = call.request.queryParameters["to"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }; val archived = call.request.queryParameters["includeArchived"] == "true"
                val notifications = NotificationRepository.list(principal, severity, action, projectId, from, to, archived, limit, offset).map { row -> NotificationResponse(row.id.toString(), row.title, row.message, row.severity, row.action, row.createdAt.toString(), row.readAt != null) }; val total = NotificationRepository.count(principal, severity, action, projectId, from, to, archived)
                call.respond(PaginatedResponse(notifications, total, limit, offset, offset + notifications.size < total))
            }
            post("/api/admin/notifications/{id}/{state}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_notification_id", "Invalid notification ID"); return@post }
                val state = call.parameters["state"] ?: ""
                if (state !in setOf("read", "dismissed", "archived")) { call.respondError(HttpStatusCode.BadRequest, "invalid_notification_state", "State must be read, dismissed, or archived"); return@post }
                val recipient = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                if (!NotificationRepository.mark(id, recipient, state)) { call.respondError(HttpStatusCode.NotFound, "notification_not_found", "Notification not found"); return@post }
                call.respond(mapOf("status" to state, "id" to id.toString()))
            }
            get("/api/admin/notifications/stream") {
                val recipient = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        var lastSeen: String? = null
                        repeat(20) {
                            val latest = NotificationRepository.list(recipient, null, null, null, null, null, false, 10)
                            val fresh = latest.filter { it.id.toString() != lastSeen }
                            if (fresh.isNotEmpty()) {
                                lastSeen = fresh.first().id.toString()
                                val payload = fresh.map { NotificationResponse(it.id.toString(), it.title, it.message, it.severity, it.action, it.createdAt.toString(), it.readAt != null) }
                                write("data: ${kotlinx.serialization.json.Json.encodeToString(payload)}\n\n")
                                flush()
                            } else {
                                write(": heartbeat\n\n")
                                flush()
                            }
                            kotlinx.coroutines.delay(3000.milliseconds)
                        }
                    } catch (_: Throwable) {
                        // The browser commonly closes this stream during navigation or reload.
                    }
                }
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
