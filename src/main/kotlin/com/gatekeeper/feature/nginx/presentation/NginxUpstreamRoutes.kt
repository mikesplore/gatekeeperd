package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.nginx.application.ConvertSiteToDeploymentManagedUseCase
import com.gatekeeper.feature.nginx.application.RepairDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.nginxUpstreamRoutes(
    siteRepository: SiteRepository,
    dockerService: DockerGateway,
    deploymentJobs: DeploymentJobRepository,
    nginxSiteStorage: NginxSiteStorage,
    repairDeploymentUpstreamUseCase: RepairDeploymentUpstreamUseCase,
    convertSiteToDeploymentManagedUseCase: ConvertSiteToDeploymentManagedUseCase
) {
    authenticate("auth-jwt") {
        get("/api/admin/nginx/upstream-migration") {
            val report = siteRepository.findAll().map { site ->
                val serviceId = site.serviceId
                val configured =
                    serviceId != null && deploymentJobs.configurationIdForService(site.projectId, serviceId) != null
                val target = activeDeploymentTarget(site)
                val result = when {
                    site.upstreamKind == "DEPLOYMENT_MANAGED" -> "already_migrated"
                    site.upstreamMode == UpstreamMode.DOCKER_DISCOVERY && target == null -> "needs_adoption_first"
                    !configured && site.upstreamMode == UpstreamMode.DOCKER_DISCOVERY -> "needs_adoption_first"
                    !configured -> "explicit_site_unchanged"
                    target == null -> "no_active_runtime"
                    else -> "ready_to_apply"
                }
                UpstreamMigrationRow(site.id.toString(), site.projectSlug, site.domain, site.upstreamKind, result)
            }
            call.respond(report)
        }

        post("/api/admin/nginx/upstream-migration/sites/{siteId}") {
            val siteId =
                call.parameters["siteId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_site_id",
                        "A valid site ID is required"
                    )
            val site = siteRepository.findById(siteId)
                ?: return@post call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found")
            val serviceId = site.serviceId
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "service_missing",
                    "Site must be attached to a service before migration"
                )
            if (site.upstreamKind == "DEPLOYMENT_MANAGED") return@post call.respond(
                mapOf(
                    "success" to true,
                    "status" to "already_migrated"
                )
            )
            if (deploymentJobs.configurationIdForService(site.projectId, serviceId) == null) {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "deployment_configuration_missing",
                    "Create or adopt a deployment configuration before converting this site"
                )
            }
            val target = activeDeploymentTarget(site)
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "needs_adoption_first",
                    "This service has no active deployment runtime to target"
                )
            val summary = deploymentJobs.configurationSummaryForService(site.projectId, serviceId)
            val legacyPrimary = site.upstreamExplicitPort ?: target.containerPort
            if (site.upstreamMode == UpstreamMode.EXPLICIT_PORT) {
                if (summary?.containerPort != null && legacyPrimary != null && summary.containerPort != legacyPrimary) {
                    return@post call.respondError(
                        HttpStatusCode.Conflict,
                        "primary_port_mismatch",
                        "Configured primary container port differs from the legacy explicit upstream port; resolve it in deployment settings first"
                    )
                }
            }
            if (summary?.containerPort == null && legacyPrimary != null) {
                deploymentJobs.setPrimaryContainerPortForService(site.projectId, serviceId, legacyPrimary)
            }
            val migrated = convertSiteToDeploymentManagedUseCase.execute(site, target.port)
            if (!migrated) return@post call.respondError(
                HttpStatusCode.InternalServerError,
                "upstream_migration_failed",
                "Site conversion failed; the previous route was restored where possible"
            )
            val backupName = site.projectSlug?.let { slug -> nginxSiteStorage.listBackups(slug).firstOrNull()?.name }
            call.respond(
                mapOf(
                    "success" to true,
                    "status" to "deployment_managed",
                    "serviceId" to serviceId.toString(),
                    "backupName" to backupName
                )
            )
        }



        post("/api/admin/nginx/upstream-repair/{serviceId}") {
            val serviceId =
                call.parameters["serviceId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_service_id",
                        "A valid service ID is required"
                    )
            val environment =
                call.request.queryParameters["environment"]?.trim()?.takeIf { it.isNotEmpty() } ?: "production"
            val site = siteRepository.findByServiceId(serviceId)
                ?: return@post call.respondError(
                    HttpStatusCode.NotFound,
                    "site_not_found",
                    "Service has no nginx site"
                )
            if (site.upstreamKind != "DEPLOYMENT_MANAGED") {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "site_not_deployment_managed",
                    "Only deployment-managed sites can be repaired through this operation"
                )
            }
            val active = DeploymentApplicationService.activeRuntime(serviceId, environment)
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "active_runtime_missing",
                    "No active runtime is available as a repair target"
                )
            val selectedPort = site.upstreamContainerPort
            val primaryPort = selectedPort ?: active.containerPort ?: active.ports.keys.singleOrNull()
            ?: return@post call.respondError(
                HttpStatusCode.Conflict,
                "primary_port_missing",
                "Active runtime has no unambiguous primary container port"
            )
            val hostPort = active.ports[primaryPort] ?: active.hostPort
            ?: return@post call.respondError(
                HttpStatusCode.Conflict,
                "host_port_missing",
                "Active runtime has no published primary port"
            )
            val containerName = active.name
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "active_container_missing",
                    "Active runtime has no Docker container identity"
                )
            if (dockerService.containerHealth(containerName) != "running") {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "active_container_not_running",
                    "The active runtime container is not running; restore or redeploy it before repairing nginx"
                )
            }
            val runtime = dockerService.adoptionDetails(containerName)
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "active_container_missing",
                    "The active runtime container no longer exists"
                )
            if (runtime.ports[primaryPort] != hostPort) {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "active_port_mismatch",
                    "The active container no longer exposes the persisted host port; redeploy or adopt the runtime before repairing nginx"
                )
            }
            if (!repairDeploymentUpstreamUseCase.execute(
                    serviceId,
                    site.domain,
                    site.tlsMode != TlsMode.HTTP_ONLY,
                    hostPort
                )
            ) {
                return@post call.respondError(
                    HttpStatusCode.BadGateway,
                    "upstream_repair_failed",
                    "Nginx upstream repair or probe failed"
                )
            }
            DeploymentApplicationService.clearRepairRequired(serviceId, environment)
            call.respond(
                mapOf(
                    "success" to true,
                    "status" to "repaired",
                    "serviceId" to serviceId.toString(),
                    "environment" to environment,
                    "hostPort" to hostPort
                )
            )
        }
    }
}

private fun activeDeploymentTarget(site: SiteRecord): DeploymentUpstreamResolver.Target? {
    val serviceId = site.serviceId ?: return null
    val active = DeploymentApplicationService.activeRuntime(serviceId, "production") ?: return null
    val containerPort =
        site.upstreamContainerPort ?: active.containerPort ?: active.ports.keys.singleOrNull() ?: return null
    val hostPort = active.ports[containerPort]
        ?: active.hostPort?.takeIf { active.containerPort == containerPort }
        ?: return null
    return DeploymentUpstreamResolver.Target("127.0.0.1", hostPort, active.name, containerPort)
}