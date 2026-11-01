package com.gatekeeper.admin.service


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.respondError
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentVersionMetadata
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.deployment.domain.repository.ActiveDeploymentInspection
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.service.domain.repository.ServiceRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.*
import java.util.UUID
import java.math.BigDecimal
import org.koin.ktor.ext.get

fun Route.registerServiceManagementRoutes(dependencies: ServiceAdminDependencies) {
    val projects = dependencies.projects
    val auditRepository = dependencies.auditRepository
    val environmentSets = dependencies.environmentSets
    val scribed = dependencies.scribed
    val deploymentJobs = dependencies.deploymentJobs
    val serviceDomains = dependencies.serviceDomains
    val serviceRepository = dependencies.serviceRepository
    val siteRepository = dependencies.siteRepository
    val nginxSiteStorage = dependencies.nginxSiteStorage
    val docker = dependencies.docker
    val validator = dependencies.validator
    get("/api/admin/projects/{projectId}/services") {
        val projectId = call.serviceProjectId(projects) ?: return@get
        if (projects.findActiveById(projectId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        call.respond(serviceRepository.listByProjectId(projectId).map { it.toView() })
    }

    post("/api/admin/projects/{projectId}/services") {
        val projectId = call.serviceProjectId(projects) ?: return@post
        if (projects.findActiveById(projectId) == null) {
            return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        val body = runCatching { call.receive<CreateServiceRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A service name is required")
        val name = body.name.trim()
        val serviceType = body.serviceType.trim().lowercase()
        if (serviceType !in ServiceApplicationRules.supportedTypes) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_type", "Unsupported service type")
        }
        if (!ServiceApplicationRules.validName(name) || name.equals("default", ignoreCase = true)) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_name", "Service name is invalid or reserved")
        }
        val service = runCatching { serviceRepository.create(projectId, name, serviceType) }.getOrElse {
            return@post call.respondError(HttpStatusCode.Conflict, "service_create_conflict", "A service with that name already exists")
        }
        call.respond(HttpStatusCode.Created, service.toView())
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        call.respond(service.toView())
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/domains") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val runtimePorts = DeploymentApplicationService.activeRuntime(service.id, "production")?.ports?.keys.orEmpty()
        call.respond(ServiceDomainBindingsResponse(
            serviceId = service.id.toString(),
            publishedPorts = runtimePorts.sorted(),
            bindings = serviceDomains.reconcilePorts(service.id, runtimePorts).map { it.toView() },
            unboundPorts = serviceDomains.unboundPorts(service.id, runtimePorts)
        ))
    }

    post("/api/admin/projects/{projectId}/services/{serviceId}/domains") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@post
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val body = runCatching { call.receive<ServiceDomainBindingRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain binding request")
        val domain = runCatching { validator.requireValidHostname(body.domain) }.getOrElse {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
        }
        val ports = DeploymentApplicationService.activeRuntime(service.id, "production")?.ports?.keys.orEmpty()
        if (ports.size > 1 && body.containerPort == null) {
            return@post call.respondError(HttpStatusCode.BadRequest, "container_port_required", "Select a container port")
        }
        if (body.containerPort != null && body.containerPort !in 1..65535) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_container_port", "containerPort must be between 1 and 65535")
        }
        if (body.containerPort != null && body.containerPort !in ports) {
            return@post call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port is not published")
        }
        if (serviceDomains.findByDomain(domain) != null) {
            return@post call.respondError(HttpStatusCode.Conflict, "domain_in_use", "Domain is already bound")
        }
        val binding = serviceDomains.add(service.id, domain, body.containerPort ?: ports.singleOrNull())
        call.respond(HttpStatusCode.Created, binding.toView())
    }

    put("/api/admin/projects/{projectId}/services/{serviceId}/domains/{bindingId}") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@put
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val bindingId = runCatching { UUID.fromString(call.parameters["bindingId"]) }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_binding_id", "Binding ID is invalid")
        val body = runCatching { call.receive<ServiceDomainBindingRequest>() }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain binding request")
        val domain = runCatching { validator.requireValidHostname(body.domain) }.getOrElse {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
        }
        val existing = serviceDomains.findByDomain(domain)
        if (existing != null && existing.id != bindingId) {
            return@put call.respondError(HttpStatusCode.Conflict, "domain_in_use", "Domain is already bound")
        }
        val ports = DeploymentApplicationService.activeRuntime(service.id, "production")?.ports?.keys.orEmpty()
        if (body.containerPort != null && body.containerPort !in ports) {
            return@put call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port is not published")
        }
        val binding = serviceDomains.update(bindingId, service.id, domain, body.containerPort ?: ports.singleOrNull())
            ?: return@put call.respondError(HttpStatusCode.NotFound, "domain_not_bound", "Domain binding was not found")
        call.respond(binding.toView())
    }

    delete("/api/admin/projects/{projectId}/services/{serviceId}/domains/{bindingId}") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@delete
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@delete call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val bindingId = runCatching { UUID.fromString(call.parameters["bindingId"]) }.getOrNull()
            ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_binding_id", "Binding ID is invalid")
        if (!serviceDomains.removeById(service.id, bindingId)) {
            return@delete call.respondError(HttpStatusCode.NotFound, "domain_not_bound", "Domain binding was not found")
        }
        call.respond(mapOf("status" to "domain_removed", "bindingId" to bindingId.toString()))
    }

    patch("/api/admin/projects/{projectId}/services/{serviceId}") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@patch
        val current = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@patch call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val body = runCatching { call.receive<UpdateServiceRequest>() }.getOrNull()
            ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service update")
        if (body.isDefault == false) {
            return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_default_service", "Choose another service as default; the current default cannot be unset directly")
        }
        val name = body.name?.trim()
        if (name != null && (!ServiceApplicationRules.validName(name) || (!current.isDefault && name.equals("default", ignoreCase = true)))) {
            return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_service_name", "Service name is invalid or reserved")
        }
        if (body.accessStatus != null && body.accessStatus !in setOf("active", "blocked", "manual_block")) {
            return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_access_status", "accessStatus must be active, blocked, or manual_block")
        }
        if ((body.blockReason?.length ?: 0) > 500) {
            return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason", "blockReason must be at most 500 characters")
        }
        if ((body.blockReasonNote?.length ?: 0) > 500) {
            return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason_note", "blockReasonNote must be at most 500 characters")
        }
        val blockReasonCode = body.blockReasonCode?.let { code ->
            AccessBlockReason.entries.firstOrNull { it.value == code }
                ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason_code", "Unsupported blockReasonCode")
        }
        val serviceType = body.serviceType?.trim()?.lowercase()
        if (serviceType != null && serviceType !in ServiceApplicationRules.supportedTypes) {
            return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_service_type", "Unsupported service type")
        }
        val updated = runCatching {
            serviceRepository.update(projectId, serviceId, name, body.accessStatus, body.blockReason, blockReasonCode, body.blockReasonNote, body.isDefault == true, serviceType)
        }.getOrElse {
            return@patch call.respondError(HttpStatusCode.Conflict, "service_update_conflict", "The service update conflicts with an existing service")
        } ?: return@patch call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        call.respond(updated.toView())
    }

    delete("/api/admin/projects/{projectId}/services/{serviceId}") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@delete
        val current = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@delete call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        if (current.isDefault) {
            return@delete call.respondError(HttpStatusCode.Conflict, "default_service_required", "The default service cannot be deleted")
        }
        val boundDomains = (serviceDomains.listByService(serviceId).map { it.domain } + listOfNotNull(current.domain)).distinct()
        val sites = (siteRepository.findAllByServiceId(serviceId) + boundDomains.mapNotNull { domain ->
            siteRepository.findByDomain(domain)?.takeIf { it.projectId == projectId }
        }).distinctBy { it.id }
        val releasedDomains = (boundDomains + sites.map { it.domain }).distinct()
        val runtimeNames = DeploymentApplicationService.runtimeContainersForService(serviceId)
        val dockerInventory = if (runtimeNames.isEmpty()) emptyList() else {
            if (!docker.available) {
                return@delete call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker must be available to remove this service's deployment containers; the service was not deleted")
            }
            runCatching { docker.listContainers(all = true) }.getOrElse {
                return@delete call.respondError(HttpStatusCode.BadGateway, "docker_inspection_failed", "Docker containers could not be inspected; the service was not deleted")
            }
        }
        for (site in sites) {
            if (!nginxSiteStorage.removeSiteArtifacts(site)) {
                return@delete call.respondError(HttpStatusCode.BadGateway, "service_nginx_cleanup_failed", "Could not remove nginx configuration for ${site.domain}; the service was not deleted")
            }
        }
        if (!nginxSiteStorage.removeUpstreamInclude(serviceId)) {
            return@delete call.respondError(HttpStatusCode.BadGateway, "service_nginx_cleanup_failed", "Could not remove the service upstream configuration; the service was not deleted")
        }
        if (sites.isNotEmpty() && (!nginxSiteStorage.testNginxConfigDetailed().valid || !nginxSiteStorage.reloadNginx())) {
            return@delete call.respondError(HttpStatusCode.BadGateway, "service_nginx_reload_failed", "Nginx rejected the service cleanup; the service record was retained")
        }
        val removedContainers = mutableListOf<String>()
        if (runtimeNames.isNotEmpty()) {
            for (runtimeName in runtimeNames) {
                val container = dockerInventory.firstOrNull { it.name.removePrefix("/") == runtimeName } ?: continue
                if (container.state.equals("running", ignoreCase = true) ||
                    container.state.equals("restarting", ignoreCase = true) ||
                    container.state.equals("paused", ignoreCase = true)
                ) {
                    runCatching { docker.stopContainer(container.id, timeoutSeconds = 10) }
                }
                val removal = runCatching { docker.deleteContainer(container.id, force = true) }
                if (removal.isFailure) {
                    val stillPresent = runCatching {
                        docker.listContainers(all = true).any { it.id == container.id || it.name.removePrefix("/") == runtimeName }
                    }.getOrElse { true }
                    if (stillPresent) {
                        return@delete call.respondError(HttpStatusCode.BadGateway, "service_container_cleanup_failed", "Could not remove deployment container $runtimeName; the service was not deleted")
                    }
                }
                removedContainers += runtimeName
            }
        }
        if (!serviceRepository.delete(projectId, serviceId)) {
            return@delete call.respondError(HttpStatusCode.Conflict, "service_delete_conflict", "The service could not be deleted")
        }
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        auditRepository.write(projectId, "service_deleted", actor, "service=${current.name}; sites=${sites.size}; domains=${releasedDomains.size}; containers=${removedContainers.size}")
        call.respond(HttpStatusCode.OK, ServiceDeletionResponse(
            deleted = true,
            serviceId = serviceId.toString(),
            domainsReleased = releasedDomains,
            containersRemoved = removedContainers,
            retained = listOf("payments", "payment events", "audit logs", "notifications", "deployment/environment history")
        ))
    }
}
