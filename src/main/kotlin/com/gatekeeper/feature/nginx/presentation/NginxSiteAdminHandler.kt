package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.nginx.application.DisableProjectUseCase
import com.gatekeeper.feature.nginx.application.RollbackSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.UUID

class NginxSiteAdminHandler(
    private val disableProjectUseCase: DisableProjectUseCase,
    private val projectRepository: ProjectRepository,
    private val siteRepository: SiteRepository,
    private val nginx: NginxSiteStorage,
    private val rollback: RollbackSiteConfigUseCase,
    private val switchDeploymentUpstreamUseCase: SwitchDeploymentUpstreamUseCase,
    private val auditRepository: AuditRepository
) {
    suspend fun disable(call: ApplicationCall) {
        val slug = call.slugOrRespond() ?: return

        val disabled = disableProjectUseCase.disableProject(slug)
        if (!disabled) {
            call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to disable nginx site")
            return
        }

        val reloaded = nginx.reloadNginx()
        if (!reloaded) {
            call.respondError(
                HttpStatusCode.InternalServerError,
                "nginx_error",
                "Failed to reload nginx after disable"
            )
            return
        }

        nginxServiceLogger.info("Nginx disabled for project: $slug")
        call.respond(
            NginxDisableResponse(
                success = true,
                message = "Nginx site disabled and reloaded successfully"
            )
        )
    }

    suspend fun rollbackLatest(call: ApplicationCall) {
        val slug = call.slugOrRespond() ?: return

        val latest = runCatching { nginx.listBackups(slug).firstOrNull()?.name }
            .getOrElse {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_slug",
                    it.message ?: "Invalid site name"
                )
                return
            }

        if (latest == null) {
            call.respondError(
                HttpStatusCode.NotFound,
                "nginx_backup_not_found",
                "No nginx backup was found for this project"
            )
            return
        }

        val result = runCatching { rollback.execute(slug, latest) }
            .getOrElse {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "rollback_failed",
                    it.message ?: "Unable to roll back configuration"
                )
                return
            }

        if (!result.success) {
            call.respond(HttpStatusCode.UnprocessableEntity, result)
        } else {
            call.respond(result)
        }

        if (result.success) {
            projectRepository.findBySlug(slug)?.let { project ->
                auditRepository.write(
                    project.id,
                    "nginx_rollback",
                    "admin",
                    "Rolled back Nginx configuration to $latest"
                )
            }
        }
    }

    suspend fun rollbackUpstreamMigration(call: ApplicationCall) {
        val siteId = call.parameters["siteId"]
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_site_id",
                "A valid site ID is required"
            )

        val site = siteRepository.findById(siteId)
            ?: return call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found")

        if (site.upstreamKind != "DEPLOYMENT_MANAGED") {
            return call.respondError(
                HttpStatusCode.Conflict,
                "site_not_deployment_managed",
                "Site is not converted to deployment-managed upstream mode"
            )
        }

        val request = runCatching { call.receive<UpstreamMigrationRollbackRequest>() }.getOrNull()
            ?: return call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "A site backup name is required"
            )

        val slug = site.projectSlug
            ?: return call.respondError(
                HttpStatusCode.Conflict,
                "site_slug_missing",
                "Site has no nginx configuration name"
            )

        val result = runCatching { rollback.execute(slug, request.backupName) }.getOrElse { error ->
            return call.respondError(
                HttpStatusCode.BadRequest,
                "upstream_migration_rollback_failed",
                error.message ?: "Could not restore the saved site config"
            )
        }

        if (!result.success) {
            return call.respondError(
                HttpStatusCode.BadGateway,
                "upstream_migration_rollback_failed",
                result.message
            )
        }

        siteRepository.restoreUpstreamKind(
            site.id,
            if (site.upstreamMode == UpstreamMode.EXPLICIT_PORT) "EXPLICIT_PORT" else null
        )

        val includeRemoved = site.serviceId
            ?.let { switchDeploymentUpstreamUseCase.restoreDeploymentUpstream(it, null) }
            ?: true

        call.respond(
            mapOf(
                "success" to true,
                "status" to if (includeRemoved) "rolled_back" else "rolled_back_include_cleanup_pending",
                "serviceId" to site.serviceId?.toString()
            )
        )
    }
}

private suspend fun ApplicationCall.slugOrRespond(): String? {
    val slug = parameters["slug"]
    if (slug == null) {
        respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
        return null
    }
    return slug
}