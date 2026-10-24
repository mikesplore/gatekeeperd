package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.admin.logger
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.nginx.ResolvedCertificate
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.text.isNullOrBlank
import kotlin.text.toIntOrNull

fun Route.nginxManualConfigRoutes(
    nginxService: NginxService,
    siteRepository: SiteRepository,
){
    post("/api/admin/nginx/config/{slug}/custom-blocks") {
        val slug = call.parameters["slug"]
        if (slug.isNullOrBlank()) return@post call.respondError(
            HttpStatusCode.BadRequest,
            "missing_slug",
            "Missing nginx site slug"
        )
        val body = runCatching { call.receive<NginxCustomLocationRequest>() }.getOrElse {
            return@post call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid custom location body"
            )
        }
        val site = siteRepository.findByProjectSlug(slug)
            ?: return@post call.respondError(
                HttpStatusCode.NotFound,
                "site_not_found",
                "Managed site not found"
            )
        val location = NginxCustomLocation(
            java.util.UUID.randomUUID().toString(),
            body.path.trim(),
            body.directives.trim()
        )
        val result = runCatching {
            nginxAdminService.updateCustomLocations(
                slug,
                site,
                site.customNginxLocations + location
            ) { updated -> renderModelFromSite(slug, updated, nginxService) }
        }
            .getOrElse {
                return@post call.respondError(
                    HttpStatusCode.UnprocessableEntity,
                    "custom_location_failed",
                    it.message ?: "Unable to add custom location"
                )
            }
        call.respond(result)
        auditRepository.write(
            site.projectId,
            "nginx_custom_location_added",
            "admin",
            "Added Nginx location ${location.path} for $slug"
        )
    }

    delete("/api/admin/nginx/config/{slug}/custom-blocks/{id}") {
        val slug = call.parameters["slug"]
        val id = call.parameters["id"]
        if (slug.isNullOrBlank() || id.isNullOrBlank()) return@delete call.respondError(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "Site slug and custom block ID are required"
        )
        val site = siteRepository.findByProjectSlug(slug)
            ?: return@delete call.respondError(
                HttpStatusCode.NotFound,
                "site_not_found",
                "Managed site not found"
            )
        val location = site.customNginxLocations.firstOrNull { it.id == id }
            ?: return@delete call.respondError(
                HttpStatusCode.NotFound,
                "custom_location_not_found",
                "Custom location not found"
            )
        val result = runCatching {
            nginxAdminService.updateCustomLocations(
                slug,
                site,
                site.customNginxLocations.filterNot { it.id == id }) { updated ->
                renderModelFromSite(
                    slug,
                    updated,
                    nginxService
                )
            }
        }.getOrElse {
            return@delete call.respondError(
                HttpStatusCode.UnprocessableEntity,
                "custom_location_failed",
                it.message ?: "Unable to remove custom location"
            )
        }
        call.respond(result)
        auditRepository.write(
            site.projectId,
            "nginx_custom_location_removed",
            "admin",
            "Removed Nginx location ${location.path} for $slug"
        )
    }

    post("/api/admin/nginx/config/{slug}/blocks/{index}/preview") {
        val slug = call.parameters["slug"]
        val index = call.parameters["index"]?.toIntOrNull()
        if (slug.isNullOrBlank() || index == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "A valid slug and block index are required"
            )
            return@post
        }
        val body = runCatching { call.receive<NginxBlockUpdateRequest>() }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid block update body"
            ); return@post
        }
        val preview = runCatching {
            nginxService.previewBlockUpdate(
                slug,
                index,
                body.content
            )
        }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_block",
                it.message ?: "Unable to preview block"
            ); return@post
        }
        call.respond(mapOf("slug" to slug, "blockIndex" to index, "config" to preview))
    }

    get("/api/admin/nginx/config/{slug}") {
        val slug = call.parameters["slug"]
        if (slug.isNullOrBlank()) {
            call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
            return@get
        }
        val inspection = runCatching { nginxService.inspectSite(slug) }.getOrElse {
            call.respondError(HttpStatusCode.BadRequest, "invalid_slug", it.message ?: "Invalid site name")
            return@get
        }
        call.respond(inspection)
    }

    post("/api/admin/nginx/config/{slug}/blocks/{index}/apply") {
        val slug = call.parameters["slug"]
        val index = call.parameters["index"]?.toIntOrNull()
        if (slug.isNullOrBlank() || index == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "A valid slug and block index are required"
            )
            return@post
        }
        val body = runCatching { call.receive<NginxBlockUpdateRequest>() }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid block update body"
            ); return@post
        }
        val result = runCatching {
            nginxService.applyBlockUpdate(
                slug,
                index,
                body.content
            )
        }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_block",
                it.message ?: "Unable to apply block"
            ); return@post
        }
        if (!result.success) call.respond(HttpStatusCode.UnprocessableEntity, result) else call.respond(result)
        if (result.success) projects.findBySlug(slug)?.let { project ->
            auditRepository.write(
                project.id,
                "nginx_block_updated",
                "admin",
                "Applied Nginx block $index for $slug"
            )
        }
    }

    get("/api/admin/nginx/config/{slug}/versions") {
        val slug = call.parameters["slug"]
        if (slug.isNullOrBlank()) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "missing_slug",
                "Missing slug path parameter"
            ); return@get
        }
        val versions = runCatching { nginxService.listBackups(slug) }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_slug",
                it.message ?: "Invalid site name"
            ); return@get
        }
        call.respond(versions)
    }

    post("/api/admin/nginx/config/{slug}/rollback/{backup}") {
        val slug = call.parameters["slug"]
        val backup = call.parameters["backup"]
        if (slug.isNullOrBlank() || backup.isNullOrBlank()) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Slug and backup name are required"
            ); return@post
        }
        val result = runCatching {
            nginxService.rollback(
                slug,
                backup
            )
        }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "rollback_failed",
                it.message ?: "Unable to roll back configuration"
            ); return@post
        }
        if (!result.success) call.respond(HttpStatusCode.UnprocessableEntity, result) else call.respond(result)
        if (result.success) projects.findBySlug(slug)?.let { project ->
            auditRepository.write(
                project.id,
                "nginx_rollback",
                "admin",
                "Rolled back Nginx configuration to $backup"
            )
        }
    }
    get("/api/admin/nginx/status/{slug}") {
        val slug = call.parameters["slug"]
        if (slug == null) {
            call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
            return@get
        }

        val project = projects.findBySlug(slug)
        if (project == null) {
            call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            return@get
        }

        val enabled =
            nginxService.listConfigArtifacts().any { it.filename == slug && it.available && it.enabled }

        val service = serviceRepository.listByProjectId(project.id).firstOrNull { it.isDefault }
        val site = service?.let { siteRepository.findByProjectIdAndServiceId(project.id, it.id) }
        val domain = serviceRepository.findDefaultDomain(project.id)
        val resolvedCert = domain?.let(nginxService::resolveCertificateForDomain)
        val expiry = resolvedCert?.certificateDomain?.let(nginxService::certificateExpiry)

        call.respond(
            NginxStatusResponse(
                enabled = enabled,
                configPath = "${nginxSettings.sitesAvailablePath}/$slug",
                enabledPath = "${nginxSettings.sitesEnabledPath}/$slug",
                port = DeploymentUpstreamResolver.resolveProjectDefault(project.id, "production")?.port,
                sslEnabled = resolvedCert != null,
                certificateDomain = resolvedCert?.certificateDomain,
                domain = domain,
                certificateExpiresAt = expiry?.first,
                certificateDaysRemaining = expiry?.second
            )
        )
    }



    get("/api/admin/nginx/diagnostics") {
        call.respond(nginxService.testNginxConfigDetailed())
    }

    post("/api/admin/nginx/test") {
        call.respond(nginxService.testNginxConfigDetailed())
    }


    post("/api/admin/nginx/remove/{slug}") {
        val slug = call.parameters["slug"]
        if (slug == null) {
            call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
            return@post
        }

        val removed = nginxService.removeProject(slug)
        if (!removed) {
            call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to remove nginx site")
            return@post
        }

        val reloaded = nginxService.reloadNginx()
        if (!reloaded) {
            call.respondError(
                HttpStatusCode.InternalServerError,
                "nginx_error",
                "Failed to reload nginx after removal"
            )
            return@post
        }

        logger.info("Nginx site removed for project: $slug")
        call.respond(
            NginxDisableResponse(
                success = true,
                message = "Nginx site removed and reloaded successfully"
            )
        )
    }
}

fun renderModelFromSite(
    slug: String,
    site: SiteRecord,
    nginxService: NginxService
): NginxSiteRenderModel {
    val (upstreamHost, port) = if (site.upstreamKind == "DEPLOYMENT_MANAGED" || site.upstreamContainerPort != null) {
        if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
            site.upstreamHost to (site.upstreamExplicitPort
                ?: error("Site $slug has no persisted deployment upstream port"))
        } else {
            val target = site.serviceId?.let {
                site.upstreamContainerPort?.let { containerPort ->
                    DeploymentUpstreamResolver.resolveRuntime(it, containerPort, "production")
                } ?: DeploymentUpstreamResolver.resolveRuntime(it, "production")
            } ?: error("Could not resolve the active deployment upstream for managed site $slug")
            target.host to target.port
        }
    } else when (site.upstreamMode) {
        UpstreamMode.EXPLICIT_PORT -> site.upstreamHost to
                (site.upstreamExplicitPort ?: error("Site $slug has no explicit upstream port"))

        UpstreamMode.DOCKER_DISCOVERY -> {
            val target = site.serviceId?.let { DeploymentUpstreamResolver.resolve(it, "production") }
                ?: error("Could not resolve the active deployment upstream for Docker site $slug")
            val containerName = target.containerName
                ?: error("Active deployment runtime has no container name for Docker site $slug")
            target.host to target.port
        }
    }
    val certificate = when (site.certMode) {
        CertMode.AUTO_RESOLVE -> nginxService.resolveCertificateForDomain(site.domain)
        CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let {
            ResolvedCertificate("explicit", it, it.replace("fullchain.pem", "privkey.pem"))
        } ?: error("Site $slug has no explicit certificate path")
    }
    val tls = when (site.tlsMode) {
        TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
        TlsMode.HTTPS -> TlsRenderMode.HTTPS
        TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
    }
    return NginxSiteRenderModel(
        slug = slug,
        projectId = site.projectId,
        serviceId = site.serviceId,
        siteId = site.id,
        domain = site.domain,
        upstreamHost = upstreamHost,
        appPort = port,
        // Public TLS terminates at nginx; application containers normally speak HTTP.
        upstreamScheme = "http",
        tlsMode = tls,
        certificatePath = certificate?.certificatePath,
        certificateKeyPath = certificate?.privateKeyPath,
        upstreamMode = site.upstreamMode,
        certMode = site.certMode,
        gateEnabled = site.gateEnabled,
        bypassPaths = site.bypassPaths,
        upstreamKind = site.upstreamKind,
        customNginxLocations = site.customNginxLocations
    )
}

fun renderModelFromSite(
    slug: String,
    site: SiteRecord,
    nginxService: NginxService
): NginxSiteRenderModel {
    val (upstreamHost, port) = if (site.upstreamKind == "DEPLOYMENT_MANAGED" || site.upstreamContainerPort != null) {
        if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
            site.upstreamHost to (site.upstreamExplicitPort
                ?: error("Site $slug has no persisted deployment upstream port"))
        } else {
            val target = site.serviceId?.let {
                site.upstreamContainerPort?.let { containerPort ->
                    DeploymentUpstreamResolver.resolveRuntime(it, containerPort, "production")
                } ?: DeploymentUpstreamResolver.resolveRuntime(it, "production")
            } ?: error("Could not resolve the active deployment upstream for managed site $slug")
            target.host to target.port
        }
    } else when (site.upstreamMode) {
        UpstreamMode.EXPLICIT_PORT -> site.upstreamHost to
                (site.upstreamExplicitPort ?: error("Site $slug has no explicit upstream port"))

        UpstreamMode.DOCKER_DISCOVERY -> {
            val target = site.serviceId?.let { DeploymentUpstreamResolver.resolve(it, "production") }
                ?: error("Could not resolve the active deployment upstream for Docker site $slug")
            val containerName = target.containerName
                ?: error("Active deployment runtime has no container name for Docker site $slug")
            target.host to target.port
        }
    }
    val certificate = when (site.certMode) {
        CertMode.AUTO_RESOLVE -> nginxService.resolveCertificateForDomain(site.domain)
        CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let {
            ResolvedCertificate("explicit", it, it.replace("fullchain.pem", "privkey.pem"))
        } ?: error("Site $slug has no explicit certificate path")
    }
    val tls = when (site.tlsMode) {
        TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
        TlsMode.HTTPS -> TlsRenderMode.HTTPS
        TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
    }
    return NginxSiteRenderModel(
        slug = slug,
        projectId = site.projectId,
        serviceId = site.serviceId,
        siteId = site.id,
        domain = site.domain,
        upstreamHost = upstreamHost,
        appPort = port,
        // Public TLS terminates at nginx; application containers normally speak HTTP.
        upstreamScheme = "http",
        tlsMode = tls,
        certificatePath = certificate?.certificatePath,
        certificateKeyPath = certificate?.privateKeyPath,
        upstreamMode = site.upstreamMode,
        certMode = site.certMode,
        gateEnabled = site.gateEnabled,
        bypassPaths = site.bypassPaths,
        upstreamKind = site.upstreamKind,
        customNginxLocations = site.customNginxLocations
    )
}