package com.gatekeeper.admin.project


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.deployment.*
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ConvertSiteToDeploymentManagedUseCase
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.math.BigDecimal
import java.text.Normalizer
import java.util.UUID

fun Route.registerProjectDeploymentRoutes(dependencies: ProjectSetupDependencies) {
    val projects = dependencies.projects
    val auditRepository = dependencies.auditRepository
    val docker = dependencies.docker
    val deploymentJobs = dependencies.deploymentJobs
    val serviceDomains = dependencies.serviceDomains
    val serviceRepository = dependencies.serviceRepository
    val siteRepository = dependencies.siteRepository
    val resolver = dependencies.resolver

    get("/api/admin/deployment-history") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        var (page, total) = DeploymentApplicationService.deploymentHistoryPage(limit, offset)
        val observedContainers = if (docker.available) runCatching { docker.listContainers(all = true) }.getOrNull() else null
        if (observedContainers != null) {
            page.map { it.second }.filter { it.status == DeploymentStatus.ACTIVE.value }.forEach { active ->
                val container = active.runtimeContainerName?.let { name ->
                    observedContainers.firstOrNull { it.name.removePrefix("/") == name }
                }
                if (container == null || !container.state.equals("running", ignoreCase = true)) {
                    DeploymentApplicationService.markActiveRuntimeUnavailable(
                        active.id,
                        active.runtimeContainerName,
                        if (container == null) "Active deployment container is missing from Docker" else "Active deployment container is ${container.state}"
                    )
                }
            }
            if (page.any { (_, item) -> item.status == DeploymentStatus.ACTIVE.value &&
                    (item.runtimeContainerName == null || observedContainers.none { it.name.removePrefix("/") == item.runtimeContainerName && it.state.equals("running", ignoreCase = true) })
                }) {
                val refreshed = DeploymentApplicationService.deploymentHistoryPage(limit, offset)
                page = refreshed.first
                total = refreshed.second
            }
        }
        call.respond(
            AdminDeploymentHistoryResponse(
                items = page.map { (slug, item) ->
                    AdminDeploymentHistoryItem(
                        id = item.id.toString(), projectId = item.projectId.toString(), projectSlug = slug,
                        environment = item.environment, sourceCommit = item.sourceCommit, imageName = item.imageName,
                        imageTag = item.imageTag, imageDigest = item.imageDigest, trigger = item.triggerSource,
                        status = item.status, createdAt = item.createdAt.toString(), activeAt = item.activeAt?.toString(),
                        healthCheckResult = item.healthCheckResult, failureReason = item.failureReason,
                        credentialSetId = item.credentialSetId?.toString(), credentialSetVersion = item.credentialSetVersion,
                        secretSetId = item.secretSetId?.toString(), secretSetVersion = item.secretSetVersion,
                        canRedeploy = item.canRedeploy, canCancel = item.canCancel,
                        cutoverState = item.cutoverState, cutoverError = item.cutoverError,
                        runtimeContainerName = item.runtimeContainerName, runtimeHostPort = item.runtimeHostPort,
                        runtimePorts = item.runtimePorts.mapKeys { it.key.toString() },
                        runtimePresent = if (observedContainers == null) null else item.runtimeContainerName?.let { name -> observedContainers.any { it.name.removePrefix("/") == name } } ?: false,
                        runtimeState = item.runtimeContainerName?.let { name -> observedContainers?.firstOrNull { it.name.removePrefix("/") == name }?.state }
                            ?: if (observedContainers != null && item.runtimeContainerName != null) "missing" else null,
                        restartCount = item.runtimeContainerName?.let { name -> observedContainers?.firstOrNull { it.name.removePrefix("/") == name }?.restartCount }
                    )
                },
                total = total,
                limit = limit,
                offset = offset
            )
        )
    }

    post("/api/admin/project-setup/projects/{projectId}/deploy") {
        val projectId = call.setupProjectId() ?: return@post
        if (projects.findActiveById(projectId) == null) {
            return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        val serviceId = call.request.queryParameters["serviceId"]?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
        } ?: deploymentJobs.defaultServiceIdForProject(projectId)
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val configurationId = deploymentJobs.configurationIdForService(projectId, serviceId)
            ?: return@post call.respondError(HttpStatusCode.Conflict, "source_runtime_required", "Save source/runtime configuration before deploying")
        val deploymentId = deploymentJobs.redeployConfiguration(configurationId)
            ?: return@post call.respondError(HttpStatusCode.Conflict, "deployment_not_queued", "Deployment could not be queued")
        call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(deploymentId.toString()))
    }

    post("/api/admin/projects/{projectId}/secret-sets/rotate-and-deploy") {
        val projectId = call.setupProjectId() ?: return@post
        if (projects.findActiveById(projectId) == null) {
            return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        val request = runCatching { call.receive<ProjectSecretRotationRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid secret rotation request")
        if (request.secretEnv.keys.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_secret_keys", "Secret environment variable names are invalid")
        }
        if (!SecretValueCipher.isConfigured()) {
            return@post call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
        }
        val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
        val deploymentId = runCatching {
            deploymentJobs.rotateProjectSecretsAndDeploy(projectId, request.secretEnv, actor)
        }.getOrElse { error ->
            return@post call.respondError(HttpStatusCode.Conflict, "secret_rotation_failed", error.message ?: "Secrets could not be rotated")
        } ?: return@post call.respondError(HttpStatusCode.Conflict, "deployment_configuration_missing", "Project has no deployment configuration")
        call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(deploymentId.toString()))
    }

    get("/api/admin/projects/{slug}/overview") {
        val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
        val project = projects.findBySlug(slug)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        serviceRepository.getOrCreateDefault(project.id)
        val config = deploymentJobs.configurationSummary(project.id)
        val serviceId = deploymentJobs.defaultServiceIdForProject(project.id)
        val site = siteRepository.findByProjectIdAndServiceId(project.id, serviceId)
        val service = serviceRepository.findByProjectAndId(project.id, serviceId)
        val active = DeploymentApplicationService.activeDeploymentSummaryForService(serviceId, "production")
        val activeRuntime = DeploymentApplicationService.activeRuntime(serviceId, "production")
        val publishedPorts = activeRuntime?.ports?.takeIf { it.isNotEmpty() } ?: active?.runtimePorts.orEmpty()
        val portMappings = publishedPorts.toSortedMap().map { (containerPort, hostPort) ->
            ProjectOverviewPortMapping(containerPort, hostPort, activeRuntime?.portHostIps?.get(containerPort))
        }.ifEmpty {
            val containerPort = activeRuntime?.containerPort
            val hostPort = activeRuntime?.hostPort
            if (containerPort != null && hostPort != null) {
                listOf(ProjectOverviewPortMapping(containerPort, hostPort, activeRuntime.portHostIps[containerPort]))
            } else emptyList()
        }
        val publicExposure = com.gatekeeper.deployment.adoptedRuntimeHasPublicBinding(activeRuntime?.triggerSource, activeRuntime?.portHostIps)
        val dockerHealth = active?.containerName?.let { name ->
            runCatching {
                docker.containerHealth(name)
            }.getOrDefault("unknown")
        } ?: "not_deployed"
        val upstream = resolver.resolve(serviceId, "production")
        val financials = ProjectSetupApplicationService.financials(project, call.application.get<ProjectBalanceAdapter>())
        call.respond(ProjectOverviewResponse(
            project.id.toString(), project.slug, project.name,
            ProjectOverviewAccessLifecycle(project.status, project.blockReason, project.deploymentMode, project.serviceMode, project.lifecycleStatus),
            ProjectOverviewDesiredConfiguration(
                config?.id?.toString(), config?.environment, config?.repository, config?.gitRef, config?.registry,
                config?.imageName, config?.imageTag, config?.containerPort, config?.envKeys.orEmpty(),
                config?.secretSetId?.toString(), config?.secretSetVersion
            ),
            ProjectOverviewCurrentDeployment(
                active?.id?.toString(), active?.status ?: "none", active?.environment ?: "production", active?.triggerSource,
                active?.createdAt?.toString(), active?.activeAt?.toString(), active?.imageName, active?.imageTag,
                active?.imageDigest, active?.commitSha, dockerHealth,
                upstream?.host, upstream?.port, publicExposure, active?.credentialSetId?.toString(), active?.credentialSetVersion,
                active?.secretSetId?.toString(),
                active?.secretSetVersion,
                portMappings = portMappings
            ),
            ProjectOverviewGateway(
                site?.id?.toString(), service?.domain,
                site != null && site.domain == service?.domain, site?.tlsMode?.value,
                site?.gateEnabled?.takeIf { site.domain == service?.domain },
                site?.reconciliationStatus?.value?.takeIf { site.domain == service?.domain }, upstream?.host, upstream?.port,
                serviceDomains.listByService(serviceId).map {
                    ProjectSetupDomainBinding(it.id.toString(), it.serviceId.toString(), it.containerPort, it.domain, it.status)
                },
                serviceDomains.unboundPorts(serviceId, publishedPorts.keys)
            ),
            ProjectOverviewCustomerBilling(
                project.customerId?.toString(), project.customerName, project.customerEmail,
                project.billingName, project.billingEmail, project.billingAddress, project.currency,
                financials.first.toDouble(), financials.second.toDouble(), financials.third.toDouble(), project.dueDate?.toString()
            )
        ))
    }

    get("/api/admin/projects/{slug}/deployments/history") {
        val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
        val project = projects.findBySlug(slug)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val environment = call.request.queryParameters["environment"]?.trim()?.takeIf(String::isNotEmpty) ?: "production"
        val serviceId = call.request.queryParameters["serviceId"]?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
        }
        if (serviceId != null && serviceRepository.findByProjectAndId(project.id, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val observedContainers = if (docker.available) runCatching { docker.listContainers(all = true) }.getOrNull() else null
        val storedHistory = DeploymentApplicationService.deploymentHistory(project.id, environment, serviceId)
        if (observedContainers != null) {
            storedHistory.filter { it.status == DeploymentStatus.ACTIVE.value }.forEach { active ->
                val container = active.runtimeContainerName?.let { name ->
                    observedContainers.firstOrNull { it.name.removePrefix("/") == name }
                }
                if (container == null || !container.state.equals("running", ignoreCase = true)) {
                    DeploymentApplicationService.markActiveRuntimeUnavailable(
                        active.id,
                        active.runtimeContainerName,
                        if (container == null) "Active deployment container is missing from Docker" else "Active deployment container is ${container.state}"
                    )
                }
            }
        }
        val history = if (observedContainers == null) storedHistory
            else DeploymentApplicationService.deploymentHistory(project.id, environment, serviceId)
        val items = history.map { item ->
            val observed = item.runtimeContainerName?.let { name ->
                observedContainers?.firstOrNull { it.name.removePrefix("/") == name }
            }
            val terminal = item.status in setOf(DeploymentStatus.FAILED.value, DeploymentStatus.CANCELLED.value,
                DeploymentStatus.SUPERSEDED.value, DeploymentStatus.ROLLED_BACK.value)
            val referenced = DeploymentApplicationService.deploymentHasReferences(item.id)
            val runtimeCanBeCleaned = item.runtimeContainerName?.let { name ->
                observedContainers != null && !DeploymentApplicationService.runtimeUsedByAnotherActiveDeployment(item.id, name)
            } ?: true
            ProjectDeploymentHistoryItem(
                item.id.toString(), item.environment, item.sourceCommit, item.imageName, item.imageTag,
                item.imageDigest, item.triggerSource, item.actor, item.status, item.createdAt.toString(),
                item.activeAt?.toString(), item.healthCheckResult, item.failureReason,
                item.credentialSetId?.toString(), item.credentialSetVersion,
                item.secretSetId?.toString(), item.secretSetVersion, item.configurationId.toString(),
                buildList {
                    if (item.canRollback) add("rollback")
                    if (item.canRedeploy) add("redeploy")
                    if (item.canCancel) add("cancel")
                    if (terminal && item.cutoverState != "repair_required" && runtimeCanBeCleaned) {
                        add("delete")
                        if (referenced) add("referenced")
                    }
                },
                item.cutoverState, item.cutoverError, item.runtimeContainerName, item.runtimeHostPort,
                item.runtimePorts.mapKeys { it.key.toString() },
                runtimePresent = if (observedContainers == null) null else observed != null,
                runtimeState = observed?.state ?: if (observedContainers != null && item.runtimeContainerName != null) "missing" else null,
                restartCount = observed?.restartCount
            )
        }
            call.respond(ProjectDeploymentHistoryResponse(project.id.toString(), environment, items))
    }

    post("/api/admin/projects/{slug}/deployments/{id}/redeploy") {
        val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
        val project = projects.findBySlug(slug)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
        val item = DeploymentApplicationService.deploymentHistory(project.id, "production").firstOrNull { it.id == id }
            ?: return@post call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
        if (!item.canRedeploy) return@post call.respondError(HttpStatusCode.Conflict, "redeploy_unavailable", "Only an active deployment or the latest failed attempt for a service can be redeployed")
        val deploymentId = deploymentJobs.redeployConfiguration(item.configurationId)
            ?: return@post call.respondError(HttpStatusCode.Conflict, "redeploy_unavailable", "Deployment configuration could not be queued")
        call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(deploymentId.toString()))
    }

    post("/api/admin/projects/{slug}/deployments/{id}/cancel") {
        val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
        val project = projects.findBySlug(slug)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
        val environment = call.request.queryParameters["environment"]?.trim()?.takeIf(String::isNotEmpty) ?: "production"
        val item = DeploymentApplicationService.deploymentHistory(project.id, environment).firstOrNull { it.id == id }
            ?: return@post call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
        if (!item.canCancel) return@post call.respondError(HttpStatusCode.Conflict, "deployment_cancel_unavailable", "Deployment is no longer in a cancellable state")
        if (!get<DeploymentWorker>().cancel(id)) return@post call.respondError(HttpStatusCode.Conflict, "deployment_cancel_unavailable", "Deployment could not be cancelled because its state changed")
        val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
        auditRepository.write(project.id, "deployment_cancelled", actor, "deployment=$id")
        call.respond(ProjectSetupCancelResponse(id.toString()))
    }

    delete("/api/admin/projects/{slug}/deployments/{id}") {
        val slug = call.parameters["slug"] ?: return@delete call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
        val project = projects.findBySlug(slug)
            ?: return@delete call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
        val environment = call.request.queryParameters["environment"]?.trim()?.takeIf(String::isNotEmpty) ?: "production"
        var item = DeploymentApplicationService.deploymentHistory(project.id, environment)
            .firstOrNull { it.id == id }
            ?: return@delete call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
        if (item.status == DeploymentStatus.ACTIVE.value && docker.available) {
            val inventory = runCatching { docker.listContainers(all = true) }.getOrElse {
                return@delete call.respondError(HttpStatusCode.BadGateway, "docker_inspection_failed", "Docker runtime state could not be checked")
            }
            val container = item.runtimeContainerName?.let { name ->
                inventory.firstOrNull { it.name.removePrefix("/") == name }
            }
            if (container == null || !container.state.equals("running", ignoreCase = true)) {
                DeploymentApplicationService.markActiveRuntimeUnavailable(
                    item.id,
                    item.runtimeContainerName,
                    if (container == null) "Active deployment container is missing from Docker" else "Active deployment container is ${container.state}"
                )
                item = DeploymentApplicationService.deploymentHistory(project.id, environment).firstOrNull { it.id == id }
                    ?: return@delete call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
            }
        }
        val terminalStatuses = setOf(
            DeploymentStatus.FAILED.value,
            DeploymentStatus.CANCELLED.value,
            DeploymentStatus.SUPERSEDED.value,
            DeploymentStatus.ROLLED_BACK.value
        )
        if (item.status in terminalStatuses && item.runtimeContainerName != null) {
            if (item.cutoverState == "repair_required" ||
                DeploymentApplicationService.runtimeUsedByAnotherActiveDeployment(id, item.runtimeContainerName)
            ) {
                return@delete call.respondError(HttpStatusCode.Conflict, "deployment_delete_unavailable", "This deployment runtime is still needed by an active or repair-required route")
            }
            if (!docker.available) {
                return@delete call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker must be available to verify or clean up this deployment runtime")
            }
            val inventory = runCatching { docker.listContainers(all = true) }.getOrElse {
                return@delete call.respondError(HttpStatusCode.BadGateway, "docker_inspection_failed", "Docker runtime state could not be checked")
            }
            val runtimeExists = inventory.any { it.name.removePrefix("/") == item.runtimeContainerName }
            if (runtimeExists) {
                runCatching { docker.deleteContainer(item.runtimeContainerName, force = true) }.getOrElse {
                    return@delete call.respondError(HttpStatusCode.BadGateway, "deployment_runtime_cleanup_failed", "The terminal deployment container could not be removed")
                }
            }
            if (!DeploymentApplicationService.clearTerminalRuntimeForDeletion(id, item.runtimeContainerName)) {
                return@delete call.respondError(HttpStatusCode.Conflict, "deployment_delete_unavailable", "The deployment runtime changed while it was being cleaned up")
            }
        }
        when (DeploymentApplicationService.deleteDeployment(id)) {
            DeploymentApplicationService.DeleteDeploymentResult.DELETED -> {
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
                auditRepository.write(project.id, "deployment_deleted", actor, "deployment=$id")
                call.respond(HttpStatusCode.NoContent)
            }
            DeploymentApplicationService.DeleteDeploymentResult.ACTIVE ->
                call.respondError(HttpStatusCode.Conflict, "deployment_delete_unavailable", "Active deployments cannot be deleted")
            DeploymentApplicationService.DeleteDeploymentResult.NOT_TERMINAL ->
                call.respondError(HttpStatusCode.Conflict, "deployment_delete_unavailable", "Only terminal deployments can be deleted")
            DeploymentApplicationService.DeleteDeploymentResult.RUNTIME_ATTACHED ->
                call.respondError(HttpStatusCode.Conflict, "deployment_delete_unavailable", "This deployment runtime is still attached or in use")
            DeploymentApplicationService.DeleteDeploymentResult.RUNTIME_IN_USE ->
                call.respondError(HttpStatusCode.Conflict, "deployment_delete_unavailable", "This deployment runtime is used by another active deployment")
            DeploymentApplicationService.DeleteDeploymentResult.NOT_FOUND ->
                call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
        }
    }

    post("/api/admin/projects/{slug}/deployments/{id}/rollback") {
        val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
        val project = projects.findBySlug(slug)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
        val item = DeploymentApplicationService.deploymentHistory(project.id, "production").firstOrNull { it.id == id }
            ?: return@post call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
        if (!item.canRollback) return@post call.respondError(HttpStatusCode.Conflict, "rollback_unavailable", "Deployment cannot be used as a rollback target")
        val rollbackId = get<DeploymentWorker>().rollback(id)
            ?: return@post call.respondError(HttpStatusCode.Conflict, "rollback_unavailable", "Deployment cannot be used as a rollback target")
        call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(rollbackId.toString()))
    }
}
