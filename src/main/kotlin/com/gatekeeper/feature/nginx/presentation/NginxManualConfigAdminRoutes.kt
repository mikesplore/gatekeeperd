package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.nginx.application.*
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.RenderModelFromSite
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.authenticate

fun Route.nginxManualConfigRoutes(
    siteRepository: SiteRepository,
    nginxAdministrationService: NginxAdministrationService,
    nginxSettings: AppSettings.NginxSettings,
    auditRepository: AuditRepository,
    preview: UpdateSiteConfigBlockUseCase,
    inspector: InspectSiteConfigUseCase,
    update: UpdateSiteConfigBlockUseCase,
    projectRepository: ProjectRepository,
    nginx: NginxSiteStorage,
    serviceRepository: ServiceRepository,
    listConfigArtifactsUseCase: ListConfigArtifactsUseCase,
    certificateProvisioner: CertbotCertificateProvisioner,
    deploymentUpstreamResolver: DeploymentUpstreamResolver,
    remove: RemoveSiteConfigUseCase,
    rollback: RollbackSiteConfigUseCase,
    renderModelFromSite: RenderModelFromSite
) {
    authenticate("auth-jwt") {
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
            nginxAdministrationService.updateCustomLocations(
                slug,
                site,
                site.customNginxLocations + location
            ) { updated -> renderModelFromSite.renderModelFromSite(slug, updated) }
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
            nginxAdministrationService.updateCustomLocations(
                slug,
                site,
                site.customNginxLocations.filterNot { it.id == id }) { updated ->
                renderModelFromSite.renderModelFromSite(
                    slug,
                    updated,
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
            preview.previewBlockUpdate(
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
        val inspection = runCatching { inspector.inspectSite(slug) }.getOrElse {
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
            update.execute(
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
        if (result.success) projectRepository.findBySlug(slug)?.let { project ->
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
        val versions = runCatching { nginx.listBackups(slug) }.getOrElse {
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
            rollback.execute(
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
        if (result.success) projectRepository.findBySlug(slug)?.let { project ->
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

        val project = projectRepository.findBySlug(slug)
        if (project == null) {
            call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            return@get
        }

        val enabled =
            listConfigArtifactsUseCase.execute().any { it.filename == slug && it.available && it.enabled }

        val domain = serviceRepository.findDefaultDomain(project.id)
        val resolvedCert = domain?.let(certificateProvisioner::resolveCertificateForDomain)
        val expiry = resolvedCert?.certificateDomain?.let(certificateProvisioner::certificateExpiry)

        call.respond(
            NginxStatusResponse(
                enabled = enabled,
                configPath = "${nginxSettings.sitesAvailablePath}/$slug",
                enabledPath = "${nginxSettings.sitesEnabledPath}/$slug",
                port = deploymentUpstreamResolver.resolveProjectDefault(project.id, "production")?.port,
                sslEnabled = resolvedCert != null,
                certificateDomain = resolvedCert?.certificateDomain,
                domain = domain,
                certificateExpiresAt = expiry?.first,
                certificateDaysRemaining = expiry?.second
            )
        )
    }



    get("/api/admin/nginx/diagnostics") {
        call.respond(nginx.testNginxConfigDetailed())
    }

    post("/api/admin/nginx/test") {
        call.respond(nginx.testNginxConfigDetailed())
    }


    post("/api/admin/nginx/remove/{slug}") {
        val slug = call.parameters["slug"]
        if (slug == null) {
            call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
            return@post
        }

        val removed = remove.removeProject(slug)
        if (!removed) {
            call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to remove nginx site")
            return@post
        }

        val reloaded = nginx.reloadNginx()
        if (!reloaded) {
            call.respondError(
                HttpStatusCode.InternalServerError,
                "nginx_error",
                "Failed to reload nginx after removal"
            )
            return@post
        }

        nginxServiceLogger.info("Nginx site removed for project: $slug")
        call.respond(
            NginxDisableResponse(
                success = true,
                message = "Nginx site removed and reloaded successfully"
            )
        )
    }
    }
}
