package com.gatekeeper.admin

import com.gatekeeper.api.dto.PaginatedResponse
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.DeleteManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentDashboardData
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentsByProject
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.plugins.Metrics
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get
import java.io.File
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository as CertificateRepositoryPort
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository as IntegrationOutboxRepositoryPort
import com.gatekeeper.feature.notification.domain.repository.NotificationRepository as NotificationRepositoryPort
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteDashboardUpdate
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository

@Serializable
data class ProjectHealthResponse(
    val project: com.gatekeeper.api.dto.ProjectResponse,
    val runtimeHealth: String? = null,
    val nginxEnabled: Boolean,
    val certificateInstalled: Boolean,
    val readiness: String
)

@Serializable
data class BulkProjectRequest(
    val slugs: List<String>,
    val reason: String,
    val blockReasonCode: String? = null,
    val blockReasonNote: String? = null
)

@Serializable
data class BulkProjectResult(val slug: String, val status: String, val message: String? = null)

@Serializable
data class DashboardSummaryResponse(
    val generatedAt: String,
    val projects: Map<String, Long>,
    val payments: Map<String, Long>,
    val revenue: Map<String, String>,
    val integrations: Map<String, Long>,
    val nginx: Map<String, Long>,
    val metrics: Map<String, Long>,
    val certificateAlerts: List<String> = emptyList()
)

@Serializable
data class DashboardSiteResponse(
    val slug: String, val projectId: String, val customerId: String? = null, val customerName: String? = null,
    val domain: String, val status: String,
    val lastNginxError: String? = null, val lastDockerError: String? = null,
    val configVersion: Int, val available: Boolean, val enabled: Boolean,
    val upstreamHost: String? = null, val upstreamMode: String? = null,
    val upstreamExplicitPort: Int? = null, val runtimeHealth: String? = null,
    val resolvedUpstreamHost: String? = null, val resolvedUpstreamPort: Int? = null,
    val tlsMode: String? = null, val gateEnabled: Boolean? = null, val serviceId: String? = null
)

@Serializable
data class DashboardSiteDetailResponse(
    val site: DashboardSiteResponse, val generatedConfig: String? = null, val currentConfig: String? = null,
    val certificateInstalled: Boolean? = null, val certificateDaysRemaining: Long? = null,
    val backups: List<String> = emptyList()
)

@Serializable
data class DashboardSitesPageResponse(
    val sites: List<DashboardSiteResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
data class DashboardSiteDeleteResponse(val deleted: Boolean, val slug: String)
@Serializable
data class NginxConfigArtifactBackupResponse(val filename: String, val backup: String? = null)
@Serializable
data class NginxManualConfigActionResponse(
    val filename: String,
    val backup: String,
    val disabled: Boolean = false,
    val deleted: Boolean = false,
    val nginxTestPassed: Boolean = true,
    val reloaded: Boolean = false,
    val resolvedManagedConfigs: List<String> = emptyList()
)

@Serializable
data class DashboardCustomerResponse(
    val id: String, val name: String, val contactEmail: String? = null, val contactPhone: String? = null,
    val billingStatus: String, val projectCount: Int, val siteCount: Int = projectCount,
    val health: Map<String, Int> = emptyMap(),
    val totalBilled: Double = 0.0, val totalPaid: Double = 0.0, val balance: Double = 0.0,
    val projects: List<DashboardCustomerProjectResponse> = emptyList()
)

@Serializable
data class DashboardCustomersPageResponse(
    val customers: List<DashboardCustomerResponse>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
data class DashboardCustomerProjectResponse(
    val id: String, val slug: String, val name: String, val domain: String,
    val amountDue: Double? = null, val totalPaid: Double = 0.0, val balance: Double = 0.0,
    val status: String
)

@Serializable
data class DashboardCustomerTransactionResponse(
    val id: String, val projectId: String, val projectName: String, val projectSlug: String,
    val amount: Double, val status: String, val gatewayStatus: String,
    val provider: String, val providerReference: String, val paidAt: String? = null, val createdAt: String
)

@Serializable
data class DashboardSiteUpdateRequest(
    val domain: String? = null, val upstreamHost: String? = null, val upstreamMode: String? = null,
    val upstreamExplicitPort: Int? = null,
    val tlsMode: String? = null, val certMode: String? = null, val certExplicitPath: String? = null,
    val gateEnabled: Boolean? = null, val bypassPaths: List<String>? = null
)

@Serializable
data class DashboardCustomerCreateRequest(
    val name: String, val contactEmail: String? = null, val contactPhone: String? = null,
    val billingStatus: String = "unknown"
)

@Serializable
data class DashboardConfirmationRequest(val confirm: Boolean = false)

private fun dashboardUpstreamState(deploymentUpstreamResolver: DeploymentUpstreamResolver, site: SiteRecord, dockerService: DockerGateway?, portProbe: PortProbe): String {
    val port = when (site.upstreamMode) {
       UpstreamMode.EXPLICIT_PORT -> site.upstreamExplicitPort ?: return "unknown"
       UpstreamMode.DOCKER_DISCOVERY -> {
            val target = site.serviceId?.let {
                site.upstreamContainerPort?.let { containerPort ->
                    deploymentUpstreamResolver.resolve(it, containerPort, "production")
                } ?: deploymentUpstreamResolver.resolve(it, "production")
            } ?: return "unknown"
            val docker = dockerService ?: return "unknown"
            val name = target.containerName ?: return "unknown"
            val container = runCatching { docker.getContainer(name) }.getOrNull() ?: return "down"
            if (!container.state.equals("running", ignoreCase = true)) return "down"
            target.port
        }
    }
    val reachable = portProbe.isReachable(site.upstreamHost, port, 500)
    return if (reachable) "running" else "down"
}

private fun Throwable.isUniqueConstraintViolation(): Boolean = generateSequence(this) { it.cause }
    .filterIsInstance<SQLException>()
    .any { it.sqlState == "23505" }

private data class CustomerOwnedProject(
    val project: ProjectRecord,
    val site: SiteRecord?
)

private fun customerOwnedProjects(projectRepository: ProjectRepository,siteRepository: SiteRepository, customerId: UUID): List<CustomerOwnedProject> {
    val projects = projectRepository.findByCustomerId(customerId)
    val sites = siteRepository.findByProjectIds(projects.map { it.id })
    return projects.map { project -> CustomerOwnedProject(project, sites[project.id]) }
}

private fun dashboardSite(
    site: SiteRecord,
    projectRepository: ProjectRepository,
    customerStore: CustomerStore,
    nginxSettings: AppSettings.NginxSettings,
    deploymentUpstreamResolver: DeploymentUpstreamResolver,
    dockerService: DockerGateway? = null
): DashboardSiteResponse {
    val project = projectRepository.findById(site.projectId)
    val customer = project?.customerId?.let(customerStore::findById)
    val slug = site.projectSlug ?: site.projectId.toString()
    val availableFile = File(nginxSettings.sitesAvailablePath, slug)
    val enabledFile = File(nginxSettings.sitesEnabledPath, slug)
    val selfDomainConfig = NginxSelfDomainExclusion(nginxSettings.selfDomains).let { exclusion ->
        exclusion.excludesFile(availableFile) || exclusion.excludesFile(enabledFile)
    }
    val resolved = site.serviceId?.let {
        site.upstreamContainerPort?.let { containerPort ->
            deploymentUpstreamResolver.resolve(it, containerPort, "production")
        } ?: deploymentUpstreamResolver.resolve(it, "production")
    }
    val configuredHost = site.upstreamHost
    val configuredPort = site.upstreamExplicitPort
    val displayHost =
        if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredHost else resolved?.host
            ?: configuredHost
    val displayPort =
        if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredPort else resolved?.port
    return DashboardSiteResponse(
        slug, site.projectId.toString(), customer?.id?.toString(), customer?.name, site.domain,
        site.reconciliationStatus.value,
        site.lastNginxError, site.lastDockerError, site.configVersion,
        !selfDomainConfig && availableFile.isFile,
        !selfDomainConfig && java.nio.file.Files.isSymbolicLink(enabledFile.toPath()),
        displayHost, site.upstreamMode.value, displayPort,
        if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.DOCKER_DISCOVERY) {
            val name = resolved?.containerName
            if (name == null || dockerService == null) null else runCatching { dockerService.containerHealth(name) }.getOrNull()
        } else null,
        if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredHost else resolved?.host,
        if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredPort else resolved?.port,
        site.tlsMode.value, site.gateEnabled, site.serviceId?.toString()
    )
}

private inline fun <T> withDashboardDocker(
    docker: DockerGateway?,
    sites: List<SiteRecord>,
    block: (DockerGateway?) -> T
): T {
    val needsDocker = sites.any { it.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.DOCKER_DISCOVERY }
    return block(docker.takeIf { needsDocker })
}

private data class DashboardProjectFinancials(
    val billed: java.math.BigDecimal,
    val paid: java.math.BigDecimal,
    val balance: java.math.BigDecimal
)

private fun dashboardProjectFinancials(
    project: ProjectRecord,
    balances: ProjectBalanceAdapter
): DashboardProjectFinancials {
    val financials = balances.financials(project)
    val billed = financials.originalCharge + financials.additionalCharges - financials.discounts
    return DashboardProjectFinancials(billed, financials.paid, financials.outstanding)
}

private fun derivedBillingStatus(financials: List<DashboardProjectFinancials>): String = when {
    financials.isEmpty() -> "unknown"
    financials.sumOf { it.balance } > java.math.BigDecimal.ZERO -> "overdue"
    else -> "current"
}

@Serializable
data class NotificationResponse(
    val id: String,
    val title: String,
    val message: String,
    val severity: String,
    val action: String,
    val createdAt: String,
    val read: Boolean = false
)

fun Application.configureOperationsAdminRoutes() {
    val projects = get<ProjectRepository>()
    val nginxSettings = get<AppSettings>().nginx
    val notificationRepository = get<NotificationRepositoryPort>()
    val certificateRepository = get<CertificateRepositoryPort>()
    val auditRepository = get<AuditRepositoryPort>()
    val integrationOutboxRepository = get<IntegrationOutboxRepositoryPort>()
    val docker = get<DockerGateway>()
    val portProbe = get<PortProbe>()
    val siteRepository = get<SiteRepository>()
    val deploymentUpstreamResolver = get<DeploymentUpstreamResolver>()
    val certificateProvisioner: CertbotCertificateProvisioner = get<CertbotCertificateProvisioner>()
    val inspectSiteConfig: InspectSiteConfigUseCase = get<InspectSiteConfigUseCase>()
    val nginx: NginxSiteStorage = get<NginxSiteStorage>()
    val nginxValidation: NginxValidationService = get<NginxValidationService>()
    val render: NginxConfigRendererService = get<NginxConfigRendererService>()
    val conflictAnalyzer: NginxConflictAnalyzer = get<NginxConflictAnalyzer>()
    val applySiteConfig: ApplySiteConfigUseCase = get<ApplySiteConfigUseCase>()
    val removesite: RemoveSiteConfigUseCase = get<RemoveSiteConfigUseCase>()
    val artifacts: ListConfigArtifactsUseCase= get<ListConfigArtifactsUseCase>()
    val inspector: InspectManualConfigUseCase = get<InspectManualConfigUseCase>()
    val delManualConfigUseCase: DeleteManualConfigUseCase = get<DeleteManualConfigUseCase>()
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/metrics") {
                call.respond(Metrics.snapshot())
            }

            get("/api/admin/dashboard/sites") {
                val status = call.request.queryParameters["status"]?.lowercase()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val filtered =
                    siteRepository.findAll().filter { status == null || it.reconciliationStatus.value == status }
                val page = filtered.drop(offset).take(limit)
                val rows = withDashboardDocker(docker, page) { gateway ->
                    page.map {
                        dashboardSite(
                            it,
                            projects,
                            call.application.get<CustomerStore>(),
                            nginxSettings,
                            deploymentUpstreamResolver,
                            gateway
                        )
                    }
                }
                call.respond(
                    DashboardSitesPageResponse(
                        rows,
                        filtered.size.toLong(),
                        limit,
                        offset,
                        offset + rows.size < filtered.size
                    )
                )
            }
            get("/api/admin/dashboard/sites/{slug}") {
                val slug = call.parameters["slug"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_slug",
                        "Missing slug"
                    ); return@get
                }
                val site = siteRepository.findByProjectSlug(slug) ?: siteRepository.findByDomain(slug)
                ?: run { call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found"); return@get }
                val siteSlug = site.projectSlug ?: slug
                val inspection = inspectSiteConfig.inspectSite(siteSlug)
                val cert = certificateProvisioner.resolveCertificateForDomain(site.domain)
                val expiry = cert?.let { certificateProvisioner.certificateExpiry(it.certificateDomain) }
                val response = withDashboardDocker(docker, listOf(site)) { gateway ->
                    dashboardSite(
                        site,
                        projects,
                        call.application.get<CustomerStore>(),
                        nginxSettings,
                        deploymentUpstreamResolver,
                        gateway
                    )
                }
                call.respond(
                    DashboardSiteDetailResponse(
                        response,
                        inspection.content?.takeIf { inspection.managed },
                        inspection.content,
                        cert != null,
                        expiry?.second,
                        nginx.listBackups(siteSlug).map { it.name })
                )
            }
            patch("/api/admin/dashboard/sites/{slug}") {
                val slug = call.parameters["slug"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_slug",
                        "Missing slug"
                    ); return@patch
                }
                val site = siteRepository.findByProjectSlug(slug) ?: siteRepository.findByDomain(slug)
                ?: run { call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found"); return@patch }
                val body = runCatching { call.receive<DashboardSiteUpdateRequest>() }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Invalid site update body"
                    ); return@patch
                }
                val update = runCatching {
                    SiteDashboardUpdate(
                        domain = body.domain?.let { nginxValidation.requireValidHostname(it) }, upstreamHost = body.upstreamHost,
                        upstreamMode = body.upstreamMode?.let { UpstreamMode.valueOf(it.uppercase()) },
                        upstreamExplicitPort = body.upstreamExplicitPort?.also { require(it in 1..65535) },
                        tlsMode = body.tlsMode?.let { TlsMode.valueOf(it.uppercase()) },
                        certMode = body.certMode?.let { CertMode.valueOf(it.uppercase()) },
                        certExplicitPath = body.certExplicitPath?.let {
                            nginxValidation.requireCertificatePath(
                                it,
                                nginxSettings.sslCertPath
                            )
                        },
                        gateEnabled = body.gateEnabled, bypassPaths = body.bypassPaths
                    )
                }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_site_update",
                        it.message ?: "Invalid site update"
                    ); return@patch
                }
                val updated = siteRepository.updateDashboard(site.id, update) ?: run {
                    call.respondError(
                        HttpStatusCode.NotFound,
                        "site_not_found",
                        "Site not found"
                    ); return@patch
                }
                val resolved =
                    if (updated.upstreamMode == UpstreamMode.DOCKER_DISCOVERY) updated.serviceId?.let {
                        deploymentUpstreamResolver.resolve(
                            it,
                            "production"
                        )
                    } else null
                val port =
                    (if (updated.upstreamMode == UpstreamMode.EXPLICIT_PORT) updated.upstreamExplicitPort else resolved?.port)
                        ?: run {
                            call.respondError(
                                HttpStatusCode.UnprocessableEntity,
                                "site_configuration_invalid",
                                "No active deployment runtime is available"
                            ); return@patch
                        }
                val cert =
                    if (updated.certMode == CertMode.AUTO_RESOLVE) certificateProvisioner.resolveCertificateForDomain(
                        updated.domain
                    ) else null
                val config =render.generateNginxConfig(
                    NginxSiteRenderModel(
                        slug = slug,
                        projectId = updated.projectId,
                        domain = updated.domain,
                        upstreamHost = resolved?.host ?: updated.upstreamHost,
                        appPort = port,
                        serviceId = updated.serviceId,
                        siteId = updated.id,
                        upstreamScheme = if (updated.tlsMode == TlsMode.HTTP_ONLY) "http" else "https",
                        tlsMode = when (updated.tlsMode) {
                            TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
                            TlsMode.HTTPS -> TlsRenderMode.HTTPS
                            TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
                        },
                        certificatePath = cert?.certificatePath ?: updated.certExplicitPath,
                        certificateKeyPath = cert?.privateKeyPath,
                        upstreamMode = updated.upstreamMode,
                        certMode = updated.certMode,
                        gateEnabled = updated.gateEnabled,
                        bypassPaths = updated.bypassPaths,
                        customNginxLocations = updated.customNginxLocations
                    )
                )
                val siteSlug = updated.projectSlug ?: slug
                val conflicts = runCatching { conflictAnalyzer.findDomainConflicts(config, siteSlug) }.getOrElse { error ->
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "nginx_config_scan_failed",
                        error.message ?: "Unable to inspect existing nginx configs"
                    )
                    return@patch
                }
                if (conflicts.isNotEmpty()) {
                    call.respondNginxDomainConflicts(conflicts)
                    return@patch
                }
                val activation = applySiteConfig.enableProjectDetailed(siteSlug, config)
                if (!activation.success) {
                    val racedConflicts =
                        runCatching { conflictAnalyzer.findDomainConflicts(config, siteSlug) }.getOrNull().orEmpty()
                    if (racedConflicts.isNotEmpty()) {
                        call.respondNginxDomainConflicts(racedConflicts)
                        return@patch
                    }
                    call.respondError(
                        HttpStatusCode.UnprocessableEntity,
                        "nginx_error",
                        activation.message ?: "Validation or activation failed; previous configuration was preserved"
                    )
                    return@patch
                }
                val responseSite =
                    siteRepository.findByProjectSlug(siteSlug) ?: siteRepository.findByDomain(siteSlug) ?: updated
                call.respond(withDashboardDocker(docker, listOf(responseSite)) { gateway ->
                    dashboardSite(
                        responseSite,
                        projects,
                        call.application.get<CustomerStore>(),
                        nginxSettings,
                        deploymentUpstreamResolver,
                        gateway
                    )
                })
            }
            delete("/api/admin/dashboard/sites/{slug}") {
                val slug = call.parameters["slug"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_slug",
                        "Missing slug"
                    ); return@delete
                }
                val site = siteRepository.findByProjectSlug(slug) ?: siteRepository.findByDomain(slug)
                val removed = site?.let(nginx::removeSiteArtifacts) ?: removesite.removeProject(slug)
                if (!removed) {
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "nginx_cleanup_failed",
                        "Unable to remove nginx artifacts"
                    ); return@delete
                }
                if (!nginx.reloadNginx()) {
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "nginx_reload_failed",
                        "Nginx artifacts were removed but the active configuration could not be reloaded"
                    ); return@delete
                }
                site?.let { siteRepository.deleteById(it.id) }
                call.respond(DashboardSiteDeleteResponse(deleted = true, slug = slug))
            }
            get("/api/admin/nginx/configs") {
                call.respond(artifacts.execute())
            }
            get("/api/admin/nginx/manual-configs/{filename}") {
                val filename = call.parameters["filename"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_filename",
                        "Missing filename"
                    ); return@get
                }
                val detail =
                    runCatching { inspector.inspectManualConfig(filename) }.getOrElse { error ->
                        val message = error.message ?: "Unable to inspect nginx config"
                        val status = if (message.contains(
                                "not found",
                                ignoreCase = true
                            )
                        ) HttpStatusCode.NotFound else HttpStatusCode.Conflict
                        call.respondError(status, "manual_config_unavailable", message)
                        return@get
                    }
                call.respond(detail)
            }
            post("/api/admin/nginx/manual-configs/{filename}/disable") {
                val filename = call.parameters["filename"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_filename",
                        "Missing filename"
                    ); return@post
                }
                val body = runCatching { call.receive<DashboardConfirmationRequest>() }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "confirmation_required",
                        "Request body must contain confirm=true"
                    ); return@post
                }
                if (!body.confirm) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "confirmation_required",
                        "Set confirm=true to back up and disable this manual config"
                    ); return@post
                }
                val artifact = artifacts.execute().firstOrNull { it.filename == filename }
                    ?: run {
                        call.respondError(
                            HttpStatusCode.NotFound,
                            "config_not_found",
                            "Nginx config not found"
                        ); return@post
                    }
                if (artifact.classification != NginxConfigClassification.MANUAL) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "config_not_manual",
                        "Only unmarked manual configs can be disabled here"
                    ); return@post
                }
                if (!artifact.enabled) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "config_already_disabled",
                        "This manual config is already disabled"
                    ); return@post
                }
                val backup = runCatching { nginx.disableManualConfig(filename) }.getOrElse { error ->
                    call.respondError(
                        HttpStatusCode.UnprocessableEntity,
                        "manual_config_disable_failed",
                        error.message ?: "Unable to disable manual config"
                    )
                    return@post
                }
                call.respond(
                    NginxManualConfigActionResponse(
                        filename,
                        backup.backup,
                        disabled = true,
                        reloaded = true,
                        resolvedManagedConfigs = backup.matchingManagedConfigs
                    )
                )
            }
            delete("/api/admin/nginx/manual-configs/{filename}") {
                val filename = call.parameters["filename"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_filename",
                        "Missing filename"
                    ); return@delete
                }
                val body = runCatching { call.receive<DashboardConfirmationRequest>() }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "confirmation_required",
                        "Request body must contain confirm=true"
                    ); return@delete
                }
                if (!body.confirm) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "confirmation_required",
                        "Set confirm=true to permanently delete this disabled manual config"
                    ); return@delete
                }
                val detail = runCatching { inspector.inspectManualConfig(filename) }.getOrElse { error ->
                    val message = error.message ?: "Unable to inspect nginx config"
                    val status = if (message.contains(
                            "not found",
                            ignoreCase = true
                        )
                    ) HttpStatusCode.NotFound else HttpStatusCode.Conflict
                    call.respondError(status, "manual_config_unavailable", message)
                    return@delete
                }
                if (detail.enabled) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "manual_config_still_enabled",
                        "Disable the manual config before deleting it"
                    ); return@delete
                }
                val backup = runCatching { delManualConfigUseCase.execute(filename) }.getOrElse { error ->
                    call.respondError(
                        HttpStatusCode.UnprocessableEntity,
                        "manual_config_delete_failed",
                        error.message ?: "Unable to delete manual config"
                    )
                    return@delete
                }
                call.respond(NginxManualConfigActionResponse(filename, backup, deleted = true))
            }
            get("/api/admin/dashboard/dead-configs") {
                call.respond(artifacts.execute().filter { it.orphaned })
            }
            delete("/api/admin/dashboard/dead-configs/{filename}") {
                val filename = call.parameters["filename"] ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "missing_filename",
                        "Missing filename"
                    ); return@delete
                }
                if (filename != File(filename).name || !filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_filename",
                        "Invalid config filename"
                    ); return@delete
                }
                val body = runCatching { call.receive<DashboardConfirmationRequest>() }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "confirmation_required",
                        "Request body must contain confirm=true"
                    ); return@delete
                }
                if (!body.confirm) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "confirmation_required",
                        "Set confirm=true to move this dead config to backup"
                    ); return@delete
                }
                val artifact = artifacts.execute().firstOrNull { it.filename == filename }
                    ?: run {
                        call.respondError(
                            HttpStatusCode.NotFound,
                            "config_not_found",
                            "Nginx config not found"
                        ); return@delete
                    }
                if (!artifact.orphaned) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "config_not_orphaned",
                        "Only orphaned managed configs can be removed here"
                    ); return@delete
                }
                val source = File(nginxSettings.sitesAvailablePath, filename)
                val enabled = File(nginxSettings.sitesEnabledPath, filename)
                val enabledWasLink = java.nio.file.Files.isSymbolicLink(enabled.toPath())
                val enabledWasActive = artifact.enabled
                val enabledTarget =
                    if (enabledWasLink) runCatching { java.nio.file.Files.readSymbolicLink(enabled.toPath()) }.getOrNull() else null
                val backup =
                    if (source.isFile) File(source.parentFile, "$filename.bak-${System.currentTimeMillis()}") else null
                if (backup != null) java.nio.file.Files.move(source.toPath(), backup.toPath())
                java.nio.file.Files.deleteIfExists(enabled.toPath())
                if (enabledWasActive && !nginx.reloadNginx()) {
                    if (backup != null && backup.exists()) java.nio.file.Files.move(backup.toPath(), source.toPath())
                    when {
                        enabledWasLink && enabledTarget != null -> java.nio.file.Files.createSymbolicLink(
                            enabled.toPath(),
                            enabledTarget
                        )

                        source.isFile -> java.nio.file.Files.copy(source.toPath(), enabled.toPath())
                    }
                    nginx.reloadNginx()
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "nginx_reload_failed",
                        "Unable to reload nginx after removing the orphaned config; the config was restored"
                    )
                    return@delete
                }
                call.respond(NginxConfigArtifactBackupResponse(filename, backup?.name))
            }
            post("/api/admin/dashboard/customers") {
                val body = runCatching { call.receive<DashboardCustomerCreateRequest>() }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Invalid customer body"
                    ); return@post
                }
                if (body.name.isBlank()) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_customer",
                        "Customer name is required"
                    ); return@post
                }
                val email = body.contactEmail?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
                if (email != null && !com.gatekeeper.api.InputValidators.isValidEmail(email)) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_customer_email",
                        "Enter a valid customer email address"
                    ); return@post
                }
                val customers = call.application.get<CustomerStore>()
                if (email != null && customers.existsByContactEmail(email)) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "customer_exists",
                        "A customer with that email already exists"
                    ); return@post
                }
                val customer = try {
                    customers.create(
                        body.name.trim(),
                        email,
                        body.contactPhone?.trim()?.takeIf { it.isNotBlank() },
                        body.billingStatus
                    )
                } catch (error: Exception) {
                    if (error.isUniqueConstraintViolation()) {
                        call.respondError(
                            HttpStatusCode.Conflict,
                            "customer_exists",
                            "A customer with that email already exists"
                        )
                        return@post
                    }
                    throw error
                }
                call.respond(
                    HttpStatusCode.Created,
                    DashboardCustomerResponse(
                        customer.id.toString(),
                        customer.name,
                        customer.contactEmail,
                        customer.contactPhone,
                        customer.billingStatus,
                        0
                    )
                )
            }
            patch("/api/admin/dashboard/projects/{id}") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_project_id",
                        "Invalid project ID"
                    ); return@patch
                }
                val body = runCatching { call.receive<Map<String, String?>>() }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Invalid project body"
                    ); return@patch
                }
                val customerId = body["customerId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (body.containsKey("customerId") && customerId == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_customer_id",
                        "Invalid customer ID"
                    ); return@patch
                }
                if (customerId != null && call.application.get<CustomerStore>().findById(customerId) == null) {
                    call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@patch
                }
                val project = projects.assignCustomer(id, customerId) ?: run {
                    call.respondError(
                        HttpStatusCode.NotFound,
                        "project_not_found",
                        "Project not found"
                    ); return@patch
                }
                call.respond(project)
            }
            get("/api/admin/dashboard/customers") {
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val query = call.request.queryParameters["q"]
                val customerRepository = call.application.get<CustomerStore>()
                val (customers, total) = customerRepository.findPage(query, limit, offset)
                val responses = customers.map { customer ->
                    val owned = customerOwnedProjects(projects, siteRepository,customer.id)
                    val balances = call.application.get<ProjectBalanceAdapter>()
                    val financials = owned.map { dashboardProjectFinancials(it.project, balances) }
                    val billed = financials.sumOf { it.billed }
                    val paid = financials.sumOf { it.paid }
                    val balance = financials.sumOf { it.balance }
                    DashboardCustomerResponse(
                        customer.id.toString(),
                        customer.name,
                        customer.contactEmail,
                        customer.contactPhone,
                        derivedBillingStatus(financials),
                        owned.size,
                        owned.size,
                        emptyMap(),
                        billed.toDouble(),
                        paid.toDouble(),
                        balance.toDouble()
                    )
                }
                call.respond(
                    DashboardCustomersPageResponse(
                        responses,
                        total,
                        limit,
                        offset,
                        offset + responses.size < total
                    )
                )
            }
            get("/api/admin/dashboard/customers/{id}") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_customer_id",
                        "Invalid customer ID"
                    ); return@get
                }
                val customer = call.application.get<CustomerStore>().findById(id) ?: run {
                    call.respondError(
                        HttpStatusCode.NotFound,
                        "customer_not_found",
                        "Customer not found"
                    ); return@get
                }
                val owned = customerOwnedProjects(projects, siteRepository,id)
                val balances = call.application.get<ProjectBalanceAdapter>()
                val financials = owned.map { dashboardProjectFinancials(it.project, balances) }
                val projects = owned.map { ownedProject ->
                    val project = ownedProject.project
                    val projectFinancials = dashboardProjectFinancials(project, balances)
                    DashboardCustomerProjectResponse(
                        id = project.id.toString(), slug = project.slug, name = project.name, domain = project.domain,
                        amountDue = projectFinancials.billed.toDouble(), totalPaid = projectFinancials.paid.toDouble(),
                        balance = projectFinancials.balance.toDouble(),
                        status = project.status
                    )
                }
                call.respond(
                    DashboardCustomerResponse(
                        customer.id.toString(),
                        customer.name,
                        customer.contactEmail,
                        customer.contactPhone,
                        derivedBillingStatus(financials),
                        projects.size,
                        projects.size,
                        emptyMap(),
                        totalBilled = projects.sumOf { it.amountDue ?: 0.0 },
                        totalPaid = projects.sumOf { it.totalPaid },
                        balance = projects.sumOf { it.balance },
                        projects = projects
                    )
                )
            }
            get("/api/admin/dashboard/customers/{id}/transactions") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_customer_id",
                        "Invalid customer ID"
                    ); return@get
                }
                if (call.application.get<CustomerStore>().findById(id) == null) {
                    call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@get
                }
                val listPayments = call.application.get<ListPaymentsByProject>()
                val transactions = customerOwnedProjects(projects, siteRepository,id).flatMap { owned ->
                    listPayments(owned.project.id).map { payment ->
                        DashboardCustomerTransactionResponse(
                            payment.id.toString(),
                            owned.project.id.toString(),
                            owned.project.name,
                            owned.project.slug,
                            payment.amount.toDouble(),
                            payment.recordStatus,
                            payment.status,
                            payment.provider,
                            payment.providerReference,
                            payment.paidAt?.toString(),
                            payment.createdAt?.toString().orEmpty()
                        )
                    }
                }.sortedByDescending { it.createdAt }
                call.respond(transactions)
            }
            get("/api/admin/dashboard/summary") {
                val allProjects = projects.findAll()
                val paymentDashboard = call.application.get<GetPaymentDashboardData>()()
                val outbox = integrationOutboxRepository.summary()
                val nginxArtifacts = artifacts.execute()
                val available = nginxArtifacts.count { it.available }.toLong()
                val enabled = nginxArtifacts.count { it.enabled }.toLong()
                val selfDomains = nginxSettings.selfDomains.toSet()
                val siteCounts =
                    siteRepository.findAll().filterNot { it.domain.lowercase().trimEnd('.') in selfDomains }
                        .groupingBy { it.reconciliationStatus.value }.eachCount().mapValues { it.value.toLong() }
                val now = LocalDateTime.now()
                val certificateAlerts = certificateRepository.findAll().flatMap { certificate ->
                    buildList {
                        certificate.lastRenewalError?.let { add("certificate for ${certificate.domain} renewal failed: $it") }
                        certificate.expiresAt?.let { expires ->
                            val days = java.time.Duration.between(now, expires).toDays()
                            if (days <= 7) add("certificate for ${certificate.domain} expires in ${days.coerceAtLeast(0)} days")
                        }
                    }
                }
                call.respond(
                    DashboardSummaryResponse(
                        OffsetDateTime.now().toString(),
                        allProjects.groupingBy { it.status.lowercase() }.eachCount().mapValues { it.value.toLong() },
                        paymentDashboard.paymentCountsByStatus,
                        mapOf(
                            "thisMonth" to paymentDashboard.thisMonthRevenue,
                            "lastMonth" to paymentDashboard.lastMonthRevenue
                        ),
                        mapOf(
                            "outboxPending" to outbox.pending,
                            "outboxProcessing" to outbox.processing,
                            "outboxDeadLetter" to outbox.deadLetter,
                            "outboxDelivered" to outbox.delivered
                        ),
                        siteCounts + mapOf("availableSites" to available, "enabledSites" to enabled),
                        Metrics.snapshot(),
                        certificateAlerts
                    )
                )
            }
            get("/api/admin/notifications") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val principal = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                val projectId =
                    call.request.queryParameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val severity = call.request.queryParameters["severity"]
                val action = call.request.queryParameters["type"]
                val from =
                    call.request.queryParameters["from"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val to =
                    call.request.queryParameters["to"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val archived = call.request.queryParameters["includeArchived"] == "true"
                val notifications = notificationRepository.list(
                    principal,
                    severity,
                    action,
                    projectId,
                    from,
                    to,
                    archived,
                    limit,
                    offset
                ).map { row ->
                    NotificationResponse(
                        row.id.toString(),
                        row.title,
                        row.message,
                        row.severity,
                        row.action,
                        row.createdAt.toString(),
                        row.readAt != null
                    )
                }
                val total = notificationRepository.count(principal, severity, action, projectId, from, to, archived)
                call.respond(
                    PaginatedResponse(
                        notifications,
                        total,
                        limit,
                        offset,
                        offset + notifications.size < total
                    )
                )
            }
            post("/api/admin/notifications/{id}/{state}") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_notification_id",
                        "Invalid notification ID"
                    ); return@post
                }
                val state = call.parameters["state"] ?: ""
                if (state !in setOf("read", "dismissed", "archived")) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_notification_state",
                        "State must be read, dismissed, or archived"
                    ); return@post
                }
                val recipient = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                if (!notificationRepository.mark(id, recipient, state)) {
                    call.respondError(
                        HttpStatusCode.NotFound,
                        "notification_not_found",
                        "Notification not found"
                    ); return@post
                }
                call.respond(mapOf("status" to state, "id" to id.toString()))
            }
            get("/api/admin/notifications/stream") {
                val recipient = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        var lastSeen: String? = null
                        repeat(20) {
                            val latest = notificationRepository.list(recipient, null, null, null, null, null, false, 10)
                            val fresh = latest.filter { it.id.toString() != lastSeen }
                            if (fresh.isNotEmpty()) {
                                lastSeen = fresh.first().id.toString()
                                val payload = fresh.map {
                                    NotificationResponse(
                                        it.id.toString(),
                                        it.title,
                                        it.message,
                                        it.severity,
                                        it.action,
                                        it.createdAt.toString(),
                                        it.readAt != null
                                    )
                                }
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
                val project = projects.findBySlug(slug) ?: run {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val active = DeploymentApplicationService.activeDeploymentSummary(project.id, "production")
                val containerName = active?.containerName
                val containerHealth = containerName?.let { name ->
                    runCatching {
                        docker.containerHealth(name)
                    }.getOrNull()
                }
                val nginxEnabled = artifacts.execute().any { it.filename == slug && it.available && it.enabled }
                val certificateInstalled = certificateProvisioner.isCertificateInstalled(project.domain)
                val ready =
                    project.status == "active" && active != null && (containerHealth == null || containerHealth == "running")
                call.respond(
                    ProjectHealthResponse(
                        project = project.toResponse(call.application.get<ProjectBalanceAdapter>().financials(project)),
                        runtimeHealth = containerHealth,
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
                if (body.reason.isBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A non-empty reason is required")
                    return@post
                }
                if ((body.blockReasonNote?.length ?: body.reason.length) > 500) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_block_reason_note",
                        "blockReasonNote must be at most 500 characters"
                    )
                    return@post
                }
                val blockReasonCode = body.blockReasonCode?.let { code ->
                    com.gatekeeper.db.tables.AccessBlockReason.entries.firstOrNull { it.value == code }
                        ?: run {
                            call.respondError(
                                HttpStatusCode.BadRequest,
                                "invalid_block_reason_code",
                                "Unsupported blockReasonCode"
                            )
                            return@post
                        }
                } ?: com.gatekeeper.db.tables.AccessBlockReason.MANUAL_HOLD
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                call.respond(body.slugs.distinct().map { slug ->
                    val project = projects.findBySlug(slug)
                    if (project == null) BulkProjectResult(slug, "failed", "Project not found")
                    else {
                        projects.updateStatus(
                            project.id, "manual_block", actor, body.reason, blockReasonCode.value,
                            blockReasonCode, body.blockReasonNote?.takeIf { it.isNotBlank() } ?: body.reason
                        )
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
                    val project = projects.findBySlug(slug)
                    if (project == null) BulkProjectResult(slug, "failed", "Project not found")
                    else {
                        projects.updateStatus(project.id, "active", actor, body.reason)
                        BulkProjectResult(slug, "active")
                    }
                })
            }

            get("/api/admin/audit/export") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 1000
                val action = call.request.queryParameters["action"]
                val actor = call.request.queryParameters["actor"]
                val rows = auditRepository.findFiltered(action, actor, limit)
                val csv = buildString {
                    appendLine("id,project_id,action,actor,reason,created_at")
                    rows.forEach { row ->
                        appendLine(
                            listOf(
                                row.id,
                                row.projectId ?: "",
                                row.action,
                                row.actor,
                                row.reason ?: "",
                                row.createdAt
                            ).joinToString(",") { csvEscape(it.toString()) })
                    }
                }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=gatekeeper-audit.csv")
                call.respondText(csv, ContentType.Text.CSV)
            }
        }
    }
}

private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
