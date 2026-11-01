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

fun Route.registerProjectSetupRoutes(dependencies: ProjectSetupDependencies) {
    val projects = dependencies.projects
    val auditRepository = dependencies.auditRepository
    val docker = dependencies.docker
    val deploymentJobs = dependencies.deploymentJobs
    val serviceDomains = dependencies.serviceDomains
    val portProbe = dependencies.portProbe
    val serviceRepository = dependencies.serviceRepository
    val siteRepository = dependencies.siteRepository
    val projectRepository = dependencies.projectRepository
    val nginxValidation = dependencies.nginxValidation
    val resolver = dependencies.resolver
    val inspector = dependencies.inspector
    val switchDeploymentUpstream = dependencies.switchDeploymentUpstream
    val converter = dependencies.converter
    val nginxSiteStorage = dependencies.nginxSiteStorage
    get("/api/admin/project-setup/containers") {
        val containers = try {
            docker.listContainers(all = false).mapNotNull { listed ->
                val details = docker.adoptionDetails(listed.id) ?: return@mapNotNull null
                if (!details.state.equals("running", ignoreCase = true)) return@mapNotNull null
                AdoptableContainerView(
                    details.id.take(12), details.name, details.image, details.state, details.networks,
                    details.ports.map { (containerPort, hostPort) -> AdoptableContainerPort(containerPort, hostPort) }.sortedBy { it.containerPort },
                    details.environment.size
                )
            }
        } catch (_: Exception) {
            return@get call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Unable to list running Docker containers")
        }
        call.respond(containers)
    }

    post("/api/admin/project-setup/projects") {
        val body = runCatching { call.receive<CreateProjectSetupRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid project setup request")
        if (body.name.isBlank() || body.type.lowercase() !in setOf("frontend", "backend")) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_project", "Name and a valid project type are required")
        }
        val domain = if (body.domain.isBlank()) "" else runCatching { nginxValidation.requireValidHostname(body.domain) }.getOrElse {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
        }
        val slug = ProjectSetupApplicationService.generatedProjectSlug(projects, body.name)
        val customerId = body.customerId?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_customer", "customerId must be a valid UUID")
        } ?: return@post call.respondError(HttpStatusCode.BadRequest, "customer_required", "A customer must be associated with the project")
        if (call.application.get<CustomerStore>().findById(customerId) == null) {
            return@post call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found")
        }
        val dueDate = InputValidators.parseDueDate(body.dueDate)
        if (!body.dueDate.isNullOrBlank() && dueDate == null) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_due_date", "dueDate must use YYYY-MM-DD format")
        }
        if (body.gracePeriodDays < 0 || body.currency.length !in 3..6 || body.amountDue?.let { it < 0 } == true) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_billing", "Billing values are invalid")
        }
        val project = projects.create(
            slug = slug, name = body.name.trim(), domain = domain,
            type = body.type.lowercase(), billingName = null, billingEmail = null, billingAddress = null,
            amountDue = body.amountDue?.let(BigDecimal::valueOf), currency = body.currency.uppercase(),
            dueDate = dueDate, gracePeriodDays = body.gracePeriodDays,
            deploymentMode = "client_hosted", serviceMode = "production", customerId = customerId
        )
        call.respond(HttpStatusCode.Created, ProjectSetupCreatedResponse(project.id.toString(), project.slug))
    }

    get("/api/admin/project-setup/projects/{projectId}") {
        val projectId = call.setupProjectId() ?: return@get
        val project = projects.findActiveById(projectId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val serviceId = call.request.queryParameters["serviceId"]?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
        } ?: serviceRepository.getOrCreateDefault(projectId).id
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val config = deploymentJobs.configurationSummaryForService(projectId, serviceId)
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
        val active = DeploymentApplicationService.activeDeploymentSummaryForService(serviceId, "production")
        val latest = DeploymentApplicationService.latestDeploymentStateForService(serviceId, "production")
        val bindings = serviceDomains.listByService(serviceId).map {
            ProjectSetupDomainBinding(it.id.toString(), it.serviceId.toString(), it.containerPort, it.domain, it.status)
        }
        call.respond(ProjectSetupStatusResponse(
            projectId.toString(), project.slug, project.name, project.domain,
            config?.toSetupResponse(),
            service?.domain?.let { ProjectSetupGatewayResponse(it, "domain_saved", serviceId.toString(), bindings.firstOrNull { binding -> binding.domain == it }?.containerPort, bindings) },
            active?.id?.toString(), active?.status, latest?.first?.toString(), latest?.second, serviceId.toString()
        ))
    }

    post("/api/admin/project-setup/projects/{projectId}/adopt-container") {
        val projectId = call.setupProjectId() ?: return@post
        val project = projects.findActiveById(projectId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val request = runCatching { call.receive<AdoptContainerRequest>() }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Container ID and container port are required")
        if (request.containerId.isBlank() || request.containerPort !in 1..65535) {
            return@post call.respondError(HttpStatusCode.BadRequest, "invalid_container", "Container and a valid published port are required")
        }
        val serviceId = request.serviceId?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service", "Service ID is invalid")
        } ?: runCatching { serviceRepository.getOrCreateDefault(projectId).id }.getOrElse {
            return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Project has no service to attach this container to")
        }
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val details = docker.adoptionDetails(request.containerId)
            ?: return@post call.respondError(HttpStatusCode.NotFound, "container_not_found", "Running container was not found")
        if (!details.state.equals("running", ignoreCase = true)) {
            return@post call.respondError(HttpStatusCode.Conflict, "container_not_running", "Only running containers can be adopted")
        }
        val hostPort = details.ports[request.containerPort]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port has no published TCP host port")
        val reachable = portProbe.isReachable("127.0.0.1", hostPort, 1500)
        if (!reachable) {
            return@post call.respondError(HttpStatusCode.Conflict, "container_port_unreachable", "The container's published port is not reachable from this server")
        }
        val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
        val adopted = runCatching { deploymentJobs.createAdoptedRuntime(projectId, serviceId, details, request.containerPort, actor) }
            .getOrElse { error ->
                return@post call.respondError(HttpStatusCode.Conflict, "container_adoption_failed", error.message ?: "Container could not be adopted")
            }
        val site = siteRepository.findByServiceId(adopted.serviceId)
        val siteSlug = site?.projectSlug ?: project.slug
        val previous = DeploymentApplicationService.activeRuntime(adopted.serviceId, "production")
        val previousConfig = if (site != null) runCatching { inspector.inspectSite(siteSlug)?.content }.getOrNull() else null
        val routed = site == null || runCatching {
            if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
                switchDeploymentUpstream.switchDeploymentUpstream(adopted.serviceId, siteSlug, "127.0.0.1", request.containerPort, hostPort)
            } else {
                converter.execute(site, hostPort)
            }
        }.getOrDefault(false)
        if (!routed) {
            DeploymentApplicationService.transition(adopted.deploymentId, com.gatekeeper.db.tables.DeploymentStatus.FAILED, "Gateway validation failed while adopting the running container")
            return@post call.respondError(HttpStatusCode.BadGateway, "container_adoption_gateway_failed", "Gateway validation failed. The container remains running and the previous runtime stays active.")
        }
        val activated = runCatching { DeploymentApplicationService.activateAfterCutover(adopted.deploymentId, previous?.id) }.getOrDefault(false)
        if (!activated) {
            if (site != null) {
                previousConfig?.let { priorConfig -> runCatching { nginxSiteStorage.restoreSiteConfiguration(siteSlug, priorConfig) } }
                runCatching { siteRepository.restoreDeploymentUpstreamForSite(site) }
                val previousPort = if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
                    val primary = previous?.containerPort ?: previous?.ports?.keys?.singleOrNull()
                    primary?.let { previous?.ports?.get(it) } ?: previous?.hostPort
                } else null
                runCatching { switchDeploymentUpstream.restoreDeploymentUpstream(adopted.serviceId, previousPort) }
            }
            DeploymentApplicationService.transition(adopted.deploymentId, com.gatekeeper.db.tables.DeploymentStatus.FAILED, "Active deployment changed during container adoption")
            return@post call.respondError(HttpStatusCode.Conflict, "container_adoption_conflict", "Project runtime changed while the container was being attached; its gateway route was restored")
        }
        auditRepository.write(projectId, "container_adopted", actor, "deployment=${adopted.deploymentId} container=${details.name}")
        call.respond(HttpStatusCode.Created, AdoptContainerResponse(
            adopted.deploymentId.toString(), details.name, environmentVariableCount = details.environment.size,
            message = if (details.environment.isNotEmpty()) {
                "Running container attached without restarting it. Its environment was saved as encrypted service variables, and the previous runtime was left running."
            } else {
                "Running container attached without restarting it. The previous runtime was left running."
            }
        ))
    }

    put("/api/admin/project-setup/projects/{projectId}/source-runtime") {
        val projectId = call.setupProjectId() ?: return@put
        val project = projects.findActiveById(projectId)
            ?: return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val body = runCatching { call.receive<CreateDeploymentRequest>() }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid source/runtime configuration")
        val normalizedRepository = body.repository?.trim()?.takeIf { it.isNotEmpty() }
        if (!ProjectSetupApplicationService.validDeployment(body, normalizedRepository)) {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration", "GitHub repository, ref, registry, image, tag, environment, or optional container port is invalid")
        }
        if (body.secretEnv.isNotEmpty()) {
            return@put call.respondError(HttpStatusCode.BadRequest, "secrets_use_environment_endpoint", "Save application secrets through the service environment settings")
        }
        val configurationId = runCatching {
            deploymentJobs.upsertProjectConfiguration(projectId, body.copy(repository = normalizedRepository, projectId = projectId.toString(), triggerSource = "project_setup"))
        }.getOrElse { error ->
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration", error.message ?: "Configuration could not be saved")
        }
        call.respond(ProjectSetupSourceRuntimeResponse(configurationId.toString(), body.environment.trim()))
    }

    put("/api/admin/projects/{projectId}/environment/shared") {
        val projectId = call.setupProjectId() ?: return@put
        if (projects.findActiveById(projectId) == null) {
            return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        val body = runCatching { call.receive<ProjectSharedEnvironmentRequest>() }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid shared environment request")
        val environment = body.environment.trim().lowercase()
        if (!environment.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        }
        if (body.values.keys.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } ||
            body.values.any { (key, value) -> key.contains('=') || value.contains('\u0000') }) {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment_keys", "Environment variable names are invalid")
        }
        if (!SecretValueCipher.isConfigured()) {
            return@put call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Environment encryption is not configured")
        }
        val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
        val result = runCatching {
            deploymentJobs.updateSharedEnvironmentAndDeploy(projectId, environment, body.values, actor)
        }.getOrElse { error ->
            return@put call.respondError(HttpStatusCode.Conflict, "shared_environment_update_failed", error.message ?: "Shared environment could not be saved")
        }
        call.respond(HttpStatusCode.Accepted, ProjectEnvironmentDeployResponse(
            result.setId.toString(), result.version, result.deploymentIds.map(UUID::toString)
        ))
    }

    put("/api/admin/project-setup/projects/{projectId}/domain-gateway") {
        val projectId = call.setupProjectId() ?: return@put
        val project = projects.findActiveById(projectId)
            ?: return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val body = runCatching { call.receive<ProjectSetupGatewayRequest>() }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain/gateway configuration")
        val domain = runCatching { nginxValidation.requireValidHostname(body.domain) }.getOrElse {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
        }
        val serviceId = body.serviceId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: if (body.serviceId == null) deploymentJobs.defaultServiceIdForProject(projectId)
            else return@put call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        if (body.containerPort != null && body.containerPort !in 1..65535) {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_container_port", "containerPort must be between 1 and 65535")
        }
        val active = DeploymentApplicationService.activeRuntime(serviceId, "production")
        val publishedPorts = active?.ports?.keys.orEmpty()
        if (publishedPorts.size > 1 && body.containerPort == null) {
            return@put call.respondError(HttpStatusCode.BadRequest, "container_port_required", "Select a containerPort when the active deployment publishes multiple ports")
        }
        val selectedPort = body.containerPort ?: publishedPorts.singleOrNull()
        if (body.containerPort != null && body.containerPort !in publishedPorts) {
            return@put call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port is not published by the active deployment")
        }
        val existing = serviceDomains.findByDomain(domain)
        if (existing != null && existing.serviceId != serviceId) {
            return@put call.respondError(HttpStatusCode.Conflict, "domain_in_use", "Domain is already bound to another service")
        }
        val binding = existing ?: runCatching {
            serviceDomains.add(serviceId, domain, selectedPort)
        }.getOrElse {
            return@put call.respondError(HttpStatusCode.Conflict, "domain_binding_failed", "Domain binding could not be saved")
        }
        if (existing != null && selectedPort != null && existing.containerPort != selectedPort) {
            serviceDomains.attachPort(existing.id, selectedPort)
        }
        val bindings = serviceDomains.reconcilePorts(serviceId, publishedPorts).map {
            ProjectSetupDomainBinding(it.id.toString(), it.serviceId.toString(), it.containerPort, it.domain, it.status)
        }
        val savedBinding = bindings.first { it.id == binding.id.toString() }
        call.respond(ProjectSetupGatewayResponse(domain, "domain_saved", serviceId.toString(), savedBinding.containerPort, bindings))
    }

    delete("/api/admin/project-setup/projects/{projectId}/domain-gateway") {
        val projectId = call.setupProjectId() ?: return@delete
        val project = projects.findActiveById(projectId)
            ?: return@delete call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        val body = runCatching { call.receive<ProjectSetupGatewayRequest>() }.getOrNull()
            ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain/gateway configuration")
        val domain = runCatching { nginxValidation.requireValidHostname(body.domain) }.getOrElse {
            return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
        }
        val serviceId = body.serviceId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: if (body.serviceId == null) deploymentJobs.defaultServiceIdForProject(projectId)
            else return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@delete call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        val removed = serviceDomains.remove(serviceId, domain)
        if (!removed) {
            return@delete call.respondError(HttpStatusCode.NotFound, "domain_not_bound", "Domain is not bound to this service")
        }
        val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
        auditRepository.write(projectId, "domain_binding_removed", actor, "service=${serviceId} domain=$domain")
        val bindings = serviceDomains.listByService(serviceId).map {
            ProjectSetupDomainBinding(it.id.toString(), it.serviceId.toString(), it.containerPort, it.domain, it.status)
        }
        call.respond(ProjectSetupGatewayResponse(domain, "domain_removed", serviceId.toString(), null, bindings))
    }
}
