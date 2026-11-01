package com.gatekeeper.admin

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

fun Route.registerNginxConfigRoutes(dependencies: OperationsAdminDependencies) {
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
}
