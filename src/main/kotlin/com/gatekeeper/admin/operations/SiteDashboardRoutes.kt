package com.gatekeeper.admin.operations


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.feature.nginx.presentation.respondNginxDomainConflicts
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.io.File
import java.util.*
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.service.domain.repository.SiteDashboardUpdate
import com.gatekeeper.deployment.DeploymentApplicationService

fun Route.registerSiteDashboardRoutes(dependencies: OperationsAdminDependencies) {
    val projects = dependencies.projects
    val nginxSettings = dependencies.nginxSettings
    val docker = dependencies.docker
    val siteRepository = dependencies.siteRepository
    val deploymentUpstreamResolver = dependencies.deploymentUpstreamResolver
    val certificateProvisioner = dependencies.certificateProvisioner
    val inspectSiteConfig = dependencies.inspectSiteConfig
    val nginx = dependencies.nginx
    val nginxValidation = dependencies.nginxValidation
    val render = dependencies.render
    val conflictAnalyzer = dependencies.conflictAnalyzer
    val applySiteConfig = dependencies.applySiteConfig
    val removesite = dependencies.removesite
    val artifacts = dependencies.artifacts
    val inspector = dependencies.inspector
    val delManualConfigUseCase = dependencies.delManualConfigUseCase
    val converter = dependencies.converter

        get("/api/admin/nginx/upstream-migration") {
            val rows = siteRepository.findAll().map { site ->
                val serviceId = site.serviceId
                val active = serviceId?.let { DeploymentApplicationService.activeDeploymentSummaryForService(it, "production") }
                val result = when {
                    serviceId == null -> "explicit_site_unchanged"
                    site.upstreamKind == "DEPLOYMENT_MANAGED" -> "already_connected"
                    active == null -> "needs_adoption_first"
                    active.runtimeHostPort == null && active.runtimePorts.values.singleOrNull() == null -> "needs_adoption_first"
                    else -> "ready_to_apply"
                }
                UpstreamMigrationRow(
                    site.id.toString(), site.projectSlug, site.domain, site.upstreamKind, result,
                    if (result == "needs_adoption_first") "An active production deployment with a published port is required" else null
                )
            }
            call.respond(rows)
        }

        post("/api/admin/nginx/upstream-migration/sites/{siteId}") {
            val siteId = call.parameters["siteId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: run { call.respondError(HttpStatusCode.BadRequest, "invalid_site_id", "Site ID is invalid"); return@post }
            val site = siteRepository.findById(siteId)
                ?: run { call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found"); return@post }
            val serviceId = site.serviceId
                ?: run { call.respondError(HttpStatusCode.Conflict, "service_required", "Site is not attached to a service"); return@post }
            val active = DeploymentApplicationService.activeDeploymentSummaryForService(serviceId, "production")
                ?: run { call.respondError(HttpStatusCode.Conflict, "production_deployment_required", "An active production deployment is required"); return@post }
            val hostPort = active.runtimeHostPort ?: active.runtimePorts.values.singleOrNull()
                ?: run { call.respondError(HttpStatusCode.Conflict, "published_port_required", "The active production deployment has no unambiguous published port"); return@post }
            if (!converter.execute(site, hostPort)) {
                call.respondError(HttpStatusCode.BadGateway, "site_conversion_failed", "The Nginx site could not be connected to the active production deployment")
                return@post
            }
            call.respond(mapOf("siteId" to site.id.toString(), "upstreamKind" to "DEPLOYMENT_MANAGED", "connected" to true))
        }

        get("/api/admin/dashboard/sites") {
            val status = call.request.queryParameters["status"]?.lowercase()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            val filtered =
                siteRepository.findAll().filter { status == null || it.reconciliationStatus.value == status }
            val page = filtered.drop(offset).take(limit)
            val rows = OperationsDashboardService.withDashboardDocker(docker, page) { gateway ->
                page.map {
                    OperationsDashboardService.dashboardSite(
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
            val siteSlug = com.gatekeeper.feature.nginx.application.SiteConfigIdentityResolver(
                nginxSettings.sitesAvailablePath, nginxSettings.sitesEnabledPath
            ).resolve(site)
            val inspection = inspectSiteConfig.inspectSite(siteSlug)
            val cert = certificateProvisioner.resolveCertificateForDomain(site.domain)
            val expiry = cert?.let { certificateProvisioner.certificateExpiry(it.certificateDomain) }
            val response = OperationsDashboardService.withDashboardDocker(docker, listOf(site)) { gateway ->
                OperationsDashboardService.dashboardSite(
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
            call.respond(OperationsDashboardService.withDashboardDocker(docker, listOf(responseSite)) { gateway ->
                OperationsDashboardService.dashboardSite(
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
            val removed = site?.let(nginx::removeSiteArtifacts) ?: removesite.execute(slug)
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
}
