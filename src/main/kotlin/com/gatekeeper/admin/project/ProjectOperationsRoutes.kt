package com.gatekeeper.admin.project


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.api.respondError
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.util.*

fun Route.registerProjectOperationsRoutes(dependencies: OperationsAdminDependencies) {
    val projects = dependencies.projects
    val docker = dependencies.docker
    val certificateProvisioner = dependencies.certificateProvisioner
    val artifacts = dependencies.artifacts

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
        val projectSites = dependencies.siteRepository.findAll().filter { it.projectId == project.id }
        val projectArtifacts = artifacts.execute(projectSites)
        val nginxEnabled = projectArtifacts.any {
            it.projectId == project.id.toString() && it.available && it.enabled
        }
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
}
