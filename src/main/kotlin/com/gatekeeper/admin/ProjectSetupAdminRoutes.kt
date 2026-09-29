package com.gatekeeper.admin

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.*
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.deployment.*
import com.gatekeeper.docker.DockerService
import com.gatekeeper.nginx.DeploymentUpstreamResolver
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.requireValidHostname
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get
import java.net.InetSocketAddress
import java.net.Socket
import java.math.BigDecimal
import java.text.Normalizer
import java.util.UUID

private fun generatedProjectSlug(name: String): String {
    val base = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "project" }
        .take(54)
        .trimEnd('-')

    if (ProjectRepository.findBySlug(base, includeArchived = true) == null) return base

    while (true) {
        val salt = UUID.randomUUID().toString().replace("-", "").take(8)
        val candidate = "${base.take(64 - salt.length - 1).trimEnd('-')}-$salt"
        if (ProjectRepository.findBySlug(candidate, includeArchived = true) == null) return candidate
    }
}

@Serializable
data class CreateProjectSetupRequest(
    val name: String,
    val domain: String = "",
    val type: String = "frontend",
    val customerId: String? = null,
    val amountDue: Double? = null,
    val currency: String = "KES",
    val dueDate: String? = null,
    val gracePeriodDays: Int = 3
)

@Serializable
data class ProjectSetupCreatedResponse(val projectId: String, val slug: String, val status: String = "created")

@Serializable
data class ProjectSetupCredentialsRequest(
    val registry: String? = null,
    val registryCredentialId: String? = null,
    val username: String? = null,
    val password: String? = null,
    @Deprecated("Application environment values belong on the service environment endpoint")
    val secretEnv: Map<String, String>? = null,
    val serviceId: String? = null,
    val environment: String = "production"
)

@Serializable
data class ProjectSetupCredentialsResponse(
    val registry: String,
    val credentialConfigured: Boolean,
    val credentialVersion: Int? = null,
    val secretSetId: String? = null,
    val secretSetVersion: Int? = null,
    val deploymentId: String? = null,
    val secretEnv: String = "write-only"
)

@Serializable
data class ProjectSharedEnvironmentRequest(
    val environment: String = "production",
    val values: Map<String, String>
)

@Serializable
data class ProjectEnvironmentDeployResponse(
    val setId: String,
    val version: Int,
    val deploymentIds: List<String>
)

@Serializable
data class ProjectSetupGatewayRequest(
    val domain: String,
    val tlsMode: String = "https",
    val gateEnabled: Boolean = true,
    val serviceId: String? = null
)

@Serializable
data class ProjectSetupGatewayResponse(
    val domain: String,
    val tlsMode: String,
    val gateEnabled: Boolean,
    val status: String
)

@Serializable
data class ProjectSetupConfigurationResponse(
    val id: String,
    val repository: String?,
    val gitRef: String,
    val registry: String,
    val imageName: String,
    val imageTag: String,
    val autoDeploy: Boolean,
    val containerPort: Int?,
    val hostPort: Int?,
    val network: String,
    val restartPolicy: String,
    val environment: String,
    val env: Map<String, String>,
    val envKeys: List<String>,
    val secretSetId: String?,
    val secretSetVersion: Int?,
    val registryCredentialId: String? = null
)

@Serializable
data class ProjectSetupStatusResponse(
    val projectId: String,
    val slug: String,
    val name: String,
    val domain: String,
    val sourceRuntime: ProjectSetupConfigurationResponse? = null,
    val credentialsConfigured: Boolean = false,
    val credentialVersion: Int? = null,
    val gateway: ProjectSetupGatewayResponse? = null,
    val activeDeploymentId: String? = null,
    val activeDeploymentStatus: String? = null,
    val latestDeploymentId: String? = null,
    val latestDeploymentStatus: String? = null
)

@Serializable
data class ProjectSetupSourceRuntimeResponse(val configurationId: String, val environment: String, val status: String = "configured")

@Serializable
data class ProjectSetupDeployResponse(val deploymentId: String, val status: String = "queued")

@Serializable
data class ProjectSetupCancelResponse(val deploymentId: String, val status: String = "cancelled")

@Serializable
data class ProjectOverviewAccessLifecycle(
    val accessStatus: String,
    val blockReason: String?,
    val deploymentMode: String,
    val serviceMode: String,
    val lifecycleStatus: String
)

@Serializable
data class ProjectOverviewDesiredConfiguration(
    val configurationId: String?,
    val environment: String?,
    val repository: String?,
    val gitRef: String?,
    val registry: String?,
    val imageName: String?,
    val imageTag: String?,
    val containerPort: Int?,
    val envKeys: List<String>,
    val secretSetId: String?,
    val secretSetVersion: Int?
)

@Serializable
data class ProjectOverviewCurrentDeployment(
    val id: String?,
    val status: String,
    val environment: String,
    val triggerSource: String?,
    val createdAt: String?,
    val activeAt: String?,
    val imageName: String?,
    val imageTag: String?,
    val imageDigest: String?,
    val commitSha: String?,
    val runtimeHealth: String,
    val runtimeUpstreamHost: String?,
    val runtimeUpstreamPort: Int?,
    val credentialSetId: String?,
    val credentialSetVersion: Int?,
    val secretSetId: String?,
    val secretSetVersion: Int?
)

@Serializable
data class ProjectOverviewGateway(
    val siteId: String?,
    val domain: String,
    val configured: Boolean,
    val tlsMode: String?,
    val gateEnabled: Boolean?,
    val reconciliationStatus: String?,
    val resolvedUpstreamHost: String?,
    val resolvedUpstreamPort: Int?
)

@Serializable
data class ProjectOverviewCustomerBilling(
    val customerId: String?,
    val customerName: String?,
    val customerEmail: String?,
    val billingName: String?,
    val billingEmail: String?,
    val billingAddress: String?,
    val currency: String,
    val billed: Double,
    val paid: Double,
    val balance: Double,
    val dueDate: String?
)

@Serializable
data class ProjectOverviewResponse(
    val projectId: String,
    val slug: String,
    val name: String,
    val type: String,
    val accessLifecycle: ProjectOverviewAccessLifecycle,
    val desiredConfiguration: ProjectOverviewDesiredConfiguration,
    val currentDeployment: ProjectOverviewCurrentDeployment,
    val domainsGateway: ProjectOverviewGateway,
    val customerBilling: ProjectOverviewCustomerBilling
)

@Serializable
data class ProjectDeploymentHistoryItem(
    val id: String,
    val environment: String,
    val sourceCommit: String?,
    val imageName: String,
    val imageTag: String,
    val imageDigest: String?,
    val trigger: String,
    val actor: String?,
    val status: String,
    val createdAt: String,
    val activeAt: String?,
    val healthCheckResult: String,
    val failureReason: String?,
    val credentialSetId: String?,
    val credentialSetVersion: Int?,
    val secretSetId: String?,
    val secretSetVersion: Int?,
    val configurationId: String,
    val actions: List<String>
)

@Serializable
data class ProjectDeploymentHistoryResponse(val projectId: String, val environment: String, val items: List<ProjectDeploymentHistoryItem>)

@Serializable
data class AdminDeploymentHistoryItem(
    val id: String,
    val projectId: String,
    val projectSlug: String,
    val environment: String,
    val sourceCommit: String?,
    val imageName: String,
    val imageTag: String,
    val imageDigest: String?,
    val trigger: String,
    val status: String,
    val createdAt: String,
    val activeAt: String?,
    val healthCheckResult: String,
    val failureReason: String?,
    val credentialSetId: String?,
    val credentialSetVersion: Int?,
    val secretSetId: String?,
    val secretSetVersion: Int?,
    val canRedeploy: Boolean,
    val canCancel: Boolean
)

@Serializable
data class AdminDeploymentHistoryResponse(
    val items: List<AdminDeploymentHistoryItem>,
    val total: Long,
    val limit: Int,
    val offset: Int
)

@Serializable
data class ProjectSecretRotationRequest(val secretEnv: Map<String, String>)

@Serializable
data class ProviderCredentialMetadataView(
    val id: String, val provider: String, val type: String, val displayName: String, val scope: String,
    val version: Int, val current: Boolean, val rotatedAt: String?, val rotatedBy: String?, val createdAt: String
)

@Serializable
data class AdoptableContainerPort(val containerPort: Int, val hostPort: Int)

@Serializable
data class AdoptableContainerView(
    val id: String, val name: String, val image: String, val state: String,
    val networks: List<String>, val ports: List<AdoptableContainerPort>, val environmentVariableCount: Int
)

@Serializable
data class AdoptContainerRequest(val containerId: String, val containerPort: Int)

@Serializable
data class AdoptContainerResponse(
    val deploymentId: String, val containerName: String, val status: String = "active",
    val environmentVariableCount: Int, val message: String
)

fun Application.configureProjectSetupAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/project-setup/containers") {
                val docker = runCatching { DockerService(AppConfig.dockerSocket) }.getOrElse {
                    return@get call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is unavailable")
                }
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
                } finally {
                    docker.close()
                }
                call.respond(containers)
            }

            get("/api/admin/project-setup/provider-credentials") {
                val provider = call.request.queryParameters["provider"]?.trim()?.lowercase()
                val type = call.request.queryParameters["type"]?.trim()?.lowercase()
                call.respond(ProviderCredentialRepository.listMetadata(provider, type).map {
                    ProviderCredentialMetadataView(it.id.toString(), it.provider, it.credentialType, it.displayName, it.scope,
                        it.version, it.current, it.rotatedAt?.toString(), it.rotatedBy, it.createdAt.toString())
                })
            }
            get("/api/admin/deployment-history") {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val (page, total) = DeploymentApplicationService.deploymentHistoryPage(limit, offset)
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
                                canRedeploy = item.canRedeploy, canCancel = item.canCancel
                            )
                        },
                        total = total,
                        limit = limit,
                        offset = offset
                    )
                )
            }
            post("/api/admin/project-setup/projects") {
                val body = runCatching { call.receive<CreateProjectSetupRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid project setup request")
                if (body.name.isBlank() || body.type.lowercase() !in setOf("frontend", "backend")) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_project", "Name and a valid project type are required")
                }
                val domain = if (body.domain.isBlank()) "" else runCatching { requireValidHostname(body.domain) }.getOrElse {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
                }
                val slug = generatedProjectSlug(body.name)
                val customerId = body.customerId?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_customer", "customerId must be a valid UUID")
                } ?: return@post call.respondError(HttpStatusCode.BadRequest, "customer_required", "A customer must be associated with the project")
                if (CustomerRepository.findById(customerId) == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found")
                }
                val dueDate = InputValidators.parseDueDate(body.dueDate)
                if (!body.dueDate.isNullOrBlank() && dueDate == null) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_due_date", "dueDate must use YYYY-MM-DD format")
                }
                if (body.gracePeriodDays < 0 || body.currency.length !in 3..6 || body.amountDue?.let { it < 0 } == true) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_billing", "Billing values are invalid")
                }
                val project = ProjectRepository.create(
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
                val project = ProjectRepository.findActiveById(projectId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val serviceId = call.request.queryParameters["serviceId"]?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                } ?: ServiceRepository.getOrCreateDefault(projectId).id
                if (ServiceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val config = DeploymentJobRepository.configurationSummaryForService(projectId, serviceId)
                val credential = config?.registryCredentialId?.let(ProviderCredentialRepository::findMetadata)
                val site = SiteRepository.findByProjectIdAndServiceId(projectId, serviceId)
                val active = DeploymentApplicationService.activeDeploymentSummaryForService(serviceId, "production")
                val latest = DeploymentApplicationService.latestDeploymentStateForService(serviceId, "production")
                call.respond(ProjectSetupStatusResponse(
                    projectId.toString(), project.slug, project.name, project.domain,
                    config?.toSetupResponse(), credential != null, credential?.version,
                    site?.let { ProjectSetupGatewayResponse(it.domain, it.tlsMode.value, it.gateEnabled, it.reconciliationStatus.value) },
                    active?.id?.toString(), active?.status, latest?.first?.toString(), latest?.second
                ))
            }

            post("/api/admin/project-setup/projects/{projectId}/adopt-container") {
                val projectId = call.setupProjectId() ?: return@post
                val project = ProjectRepository.findActiveById(projectId)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val request = runCatching { call.receive<AdoptContainerRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Container ID and container port are required")
                if (request.containerId.isBlank() || request.containerPort !in 1..65535) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_container", "Container and a valid published port are required")
                }
                val docker = runCatching { DockerService(AppConfig.dockerSocket) }.getOrElse {
                    return@post call.respondError(HttpStatusCode.ServiceUnavailable, "docker_unavailable", "Docker is unavailable")
                }
                val details = try { docker.adoptionDetails(request.containerId) } finally { docker.close() }
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "container_not_found", "Running container was not found")
                if (!details.state.equals("running", ignoreCase = true)) {
                    return@post call.respondError(HttpStatusCode.Conflict, "container_not_running", "Only running containers can be adopted")
                }
                val hostPort = details.ports[request.containerPort]
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "container_port_not_published", "Selected container port has no published TCP host port")
                val reachable = runCatching {
                    Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", hostPort), 1500) }
                    true
                }.getOrDefault(false)
                if (!reachable) {
                    return@post call.respondError(HttpStatusCode.Conflict, "container_port_unreachable", "The container's published port is not reachable from this server")
                }
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
                val adopted = runCatching { DeploymentJobRepository.createAdoptedRuntime(projectId, details, request.containerPort, actor) }
                    .getOrElse { error ->
                        return@post call.respondError(HttpStatusCode.Conflict, "container_adoption_failed", error.message ?: "Container could not be adopted")
                    }
                val site = SiteRepository.findByServiceId(adopted.serviceId)
                val nginx = if (site != null) NginxService.configured() else null
                val siteSlug = site?.projectSlug ?: project.slug
                val previous = DeploymentApplicationService.activeDeploymentSummaryForService(adopted.serviceId, "production")
                val previousConfig = if (site != null) runCatching { nginx?.inspectSite(siteSlug)?.content }.getOrNull() else null
                val routed = site == null || runCatching {
                    nginx?.switchDeploymentUpstream(adopted.serviceId, siteSlug, "127.0.0.1", request.containerPort, hostPort) == true
                }.getOrDefault(false)
                if (!routed) {
                    DeploymentApplicationService.transition(adopted.deploymentId, com.gatekeeper.db.tables.DeploymentStatus.FAILED, "Gateway validation failed while adopting the running container")
                    return@post call.respondError(HttpStatusCode.BadGateway, "container_adoption_gateway_failed", "Gateway validation failed. The container remains running and the previous runtime stays active.")
                }
                val activated = runCatching { DeploymentApplicationService.activateAfterCutover(adopted.deploymentId, previous?.id) }.getOrDefault(false)
                if (!activated) {
                    if (site != null && previousConfig != null) {
                        runCatching { nginx?.restoreSiteConfiguration(siteSlug, previousConfig) }
                        runCatching { SiteRepository.restoreDeploymentUpstreamForSite(site) }
                    }
                    DeploymentApplicationService.transition(adopted.deploymentId, com.gatekeeper.db.tables.DeploymentStatus.FAILED, "Active deployment changed during container adoption")
                    return@post call.respondError(HttpStatusCode.Conflict, "container_adoption_conflict", "Project runtime changed while the container was being attached; its gateway route was restored")
                }
                AuditRepository.write(projectId, "container_adopted", actor, "deployment=${adopted.deploymentId} container=${details.name}")
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
                val project = ProjectRepository.findActiveById(projectId)
                    ?: return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val body = runCatching { call.receive<CreateDeploymentRequest>() }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid source/runtime configuration")
                val normalizedRepository = body.repository?.trim()?.takeIf { it.isNotEmpty() }
                if (!validSetupDeployment(body, normalizedRepository)) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration", "GitHub repository, ref, registry, image, tag, environment, or port is invalid")
                }
                if (body.secretEnv.isNotEmpty()) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "secrets_use_credentials_step", "Send application secrets through the credentials step")
                }
                val registryCredentialId = body.registryCredentialId?.takeIf(String::isNotBlank)?.let { raw ->
                    val id = runCatching { UUID.fromString(raw) }.getOrNull()
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credential", "Registry credential ID is invalid")
                    val metadata = ProviderCredentialRepository.findMetadata(id)
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credential", "Registry credential was not found")
                    if (metadata.provider != "docker" || metadata.credentialType != "registry" || metadata.scope != body.registry || !metadata.current) {
                        return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credential", "Select a current credential for this registry")
                    }
                    id
                }
                val configurationId = runCatching {
                    DeploymentJobRepository.upsertProjectConfiguration(projectId, body.copy(repository = normalizedRepository, registryCredentialId = registryCredentialId?.toString(), projectId = projectId.toString(), triggerSource = "project_setup"))
                }.getOrElse { error ->
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_configuration", error.message ?: "Configuration could not be saved")
                }
                call.respond(ProjectSetupSourceRuntimeResponse(configurationId.toString(), body.environment.trim()))
            }

            put("/api/admin/project-setup/projects/{projectId}/credentials") {
                val projectId = call.setupProjectId() ?: return@put
                if (ProjectRepository.findActiveById(projectId) == null) {
                    return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                val body = runCatching { call.receive<ProjectSetupCredentialsRequest>() }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid credentials request")
                val serviceId = body.serviceId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                    ?: if (body.serviceId == null) null else return@put call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                val environment = body.environment.trim().lowercase()
                if (!environment.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
                }
                val targetServiceId = serviceId ?: DeploymentJobRepository.defaultServiceIdForProject(projectId)
                val configId = DeploymentJobRepository.configurationIdForService(projectId, targetServiceId, environment)
                val config = configId?.let(DeploymentJobRepository::configurationSummaryById)
                    ?: return@put call.respondError(HttpStatusCode.Conflict, "source_runtime_required", "Save source/runtime configuration before credentials")
                if (body.secretEnv != null) return@put call.respondError(HttpStatusCode.BadRequest, "environment_use_environment_endpoint", "Application environment values must use the service environment endpoint")
                val registry = (body.registry ?: config.registry).trim().lowercase()
                if (!registry.matches(Regex("^(docker\\.io|[A-Za-z0-9.-]+(:[0-9]{1,5})?)$"))) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry", "Registry is invalid")
                }
                val username = body.username?.trim().orEmpty()
                val password = body.password.orEmpty()
                if ((username.isBlank()) != password.isBlank()) {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credentials", "Provide both username and password to save registry credentials")
                }
                if (username.isNotBlank() && !SecretValueCipher.isConfigured()) {
                    return@put call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
                }
                val providerCredential = if (username.isNotBlank()) RegistryCredentialRepository.save(registry, username, password) else null
                if (registry != config.registry) {
                    DeploymentJobRepository.updateConfiguration(config.id, UpdateDeploymentConfigurationRequest(registry = registry))
                }
                val currentCredential = body.registryCredentialId?.takeIf(String::isNotBlank)?.let { raw ->
                    val credentialId = runCatching { UUID.fromString(raw) }.getOrNull()
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credential", "Registry credential ID is invalid")
                    val credential = ProviderCredentialRepository.findMetadata(credentialId)
                        ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credential", "Registry credential was not found")
                    if (credential.provider != "docker" || credential.credentialType != "registry" || credential.scope != registry || !credential.current) {
                        return@put call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credential", "Select a current credential for this registry")
                    }
                    DeploymentJobRepository.updateConfiguration(config.id, UpdateDeploymentConfigurationRequest(registryCredentialId = credentialId.toString()))
                    credential
                }
                call.respond(ProjectSetupCredentialsResponse(
                    registry, providerCredential != null || currentCredential != null,
                    currentCredential?.version ?: providerCredential?.version
                ))
            }

            put("/api/admin/projects/{projectId}/environment/shared") {
                val projectId = call.setupProjectId() ?: return@put
                if (ProjectRepository.findActiveById(projectId) == null) {
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
                    DeploymentJobRepository.updateSharedEnvironmentAndDeploy(projectId, environment, body.values, actor)
                }.getOrElse { error ->
                    return@put call.respondError(HttpStatusCode.Conflict, "shared_environment_update_failed", error.message ?: "Shared environment could not be saved")
                }
                call.respond(HttpStatusCode.Accepted, ProjectEnvironmentDeployResponse(
                    result.setId.toString(), result.version, result.deploymentIds.map(UUID::toString)
                ))
            }

            put("/api/admin/project-setup/projects/{projectId}/domain-gateway") {
                val projectId = call.setupProjectId() ?: return@put
                val project = ProjectRepository.findActiveById(projectId)
                    ?: return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val body = runCatching { call.receive<ProjectSetupGatewayRequest>() }.getOrNull()
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid domain/gateway configuration")
                val domain = runCatching { requireValidHostname(body.domain) }.getOrElse {
                    return@put call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Domain is invalid")
                }
                val tls = TlsMode.entries.firstOrNull { it.value == body.tlsMode.lowercase() }
                    ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_tls_mode", "tlsMode must be http_only, https, or https_http2")
                val serviceId = body.serviceId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                    ?: if (body.serviceId == null) DeploymentJobRepository.defaultServiceIdForProject(projectId)
                    else return@put call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                val service = ServiceRepository.findByProjectAndId(projectId, serviceId)
                    ?: return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                if (service.isDefault) ProjectRepository.update(
                    slug = project.slug, name = null, domain = domain, type = null,
                    amountDue = null, currency = null, dueDate = null, gracePeriodDays = null
                ) ?: return@put call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val site = SiteRepository.saveSetupDraft(projectId, domain, tls, body.gateEnabled, CertMode.AUTO_RESOLVE, serviceId)
                call.respond(ProjectSetupGatewayResponse(site.domain, site.tlsMode.value, site.gateEnabled, site.reconciliationStatus.value))
            }

            post("/api/admin/project-setup/projects/{projectId}/deploy") {
                val projectId = call.setupProjectId() ?: return@post
                if (ProjectRepository.findActiveById(projectId) == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                }
                val serviceId = call.request.queryParameters["serviceId"]?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                } ?: DeploymentJobRepository.defaultServiceIdForProject(projectId)
                if (ServiceRepository.findByProjectAndId(projectId, serviceId) == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val configurationId = DeploymentJobRepository.configurationIdForService(projectId, serviceId)
                    ?: return@post call.respondError(HttpStatusCode.Conflict, "source_runtime_required", "Save source/runtime configuration before deploying")
                val deploymentId = DeploymentJobRepository.redeployConfiguration(configurationId)
                    ?: return@post call.respondError(HttpStatusCode.Conflict, "deployment_not_queued", "Deployment could not be queued")
                call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(deploymentId.toString()))
            }

            post("/api/admin/projects/{projectId}/secret-sets/rotate-and-deploy") {
                val projectId = call.setupProjectId() ?: return@post
                if (ProjectRepository.findActiveById(projectId) == null) {
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
                    DeploymentJobRepository.rotateProjectSecretsAndDeploy(projectId, request.secretEnv, actor)
                }.getOrElse { error ->
                    return@post call.respondError(HttpStatusCode.Conflict, "secret_rotation_failed", error.message ?: "Secrets could not be rotated")
                } ?: return@post call.respondError(HttpStatusCode.Conflict, "deployment_configuration_missing", "Project has no deployment configuration")
                call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(deploymentId.toString()))
            }

            get("/api/admin/projects/{slug}/overview") {
                val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
                val project = ProjectRepository.findBySlug(slug)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                ServiceRepository.getOrCreateDefault(project.id)
                val config = DeploymentJobRepository.configurationSummary(project.id)
                val site = SiteRepository.findByProjectId(project.id)
                val serviceId = site?.serviceId ?: DeploymentJobRepository.defaultServiceIdForProject(project.id)
                val active = DeploymentApplicationService.activeDeploymentSummaryForService(serviceId, "production")
                val dockerHealth = active?.containerName?.let { name ->
                    runCatching {
                        val docker = DockerService(AppConfig.dockerSocket)
                        try { docker.containerHealth(name) } finally { docker.close() }
                    }.getOrDefault("unknown")
                } ?: "not_deployed"
                val upstream = site?.serviceId?.let { DeploymentUpstreamResolver.resolve(it, "production") }
                val financials = projectOverviewFinancials(project, call.application.get<ProjectBalanceAdapter>())
                call.respond(ProjectOverviewResponse(
                    project.id.toString(), project.slug, project.name, project.type,
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
                        upstream?.host, upstream?.port, active?.credentialSetId?.toString(), active?.credentialSetVersion,
                        active?.secretSetId?.toString(), active?.secretSetVersion
                    ),
                    ProjectOverviewGateway(
                        site?.id?.toString(), site?.domain ?: project.domain,
                        site != null, site?.tlsMode?.value,
                        site?.gateEnabled, site?.reconciliationStatus?.value, upstream?.host, upstream?.port
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
                val project = ProjectRepository.findBySlug(slug)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val environment = call.request.queryParameters["environment"]?.trim()?.takeIf(String::isNotEmpty) ?: "production"
                val serviceId = call.request.queryParameters["serviceId"]?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                }
                if (serviceId != null && ServiceRepository.findByProjectAndId(project.id, serviceId) == null) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val items = DeploymentApplicationService.deploymentHistory(project.id, environment, serviceId).map { item ->
                    ProjectDeploymentHistoryItem(
                        item.id.toString(), item.environment, item.sourceCommit, item.imageName, item.imageTag,
                        item.imageDigest, item.triggerSource, item.actor, item.status, item.createdAt.toString(),
                        item.activeAt?.toString(), item.healthCheckResult, item.failureReason,
                        item.credentialSetId?.toString(), item.credentialSetVersion,
                        item.secretSetId?.toString(), item.secretSetVersion, item.configurationId.toString(),
                        buildList { if (item.canRollback) add("rollback"); if (item.canRedeploy) add("redeploy"); if (item.canCancel) add("cancel") }
                    )
                }
                    call.respond(ProjectDeploymentHistoryResponse(project.id.toString(), environment, items))
            }

            post("/api/admin/projects/{slug}/deployments/{id}/redeploy") {
                val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
                val project = ProjectRepository.findBySlug(slug)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
                val item = DeploymentApplicationService.deploymentHistory(project.id, "production").firstOrNull { it.id == id }
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
                if (!item.canRedeploy) return@post call.respondError(HttpStatusCode.Conflict, "redeploy_unavailable", "Only an active deployment or the latest failed attempt for a service can be redeployed")
                val deploymentId = DeploymentJobRepository.redeployConfiguration(item.configurationId)
                    ?: return@post call.respondError(HttpStatusCode.Conflict, "redeploy_unavailable", "Deployment configuration could not be queued")
                call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(deploymentId.toString()))
            }

            post("/api/admin/projects/{slug}/deployments/{id}/cancel") {
                val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
                val project = ProjectRepository.findBySlug(slug)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
                val environment = call.request.queryParameters["environment"]?.trim()?.takeIf(String::isNotEmpty) ?: "production"
                val item = DeploymentApplicationService.deploymentHistory(project.id, environment).firstOrNull { it.id == id }
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
                if (!item.canCancel) return@post call.respondError(HttpStatusCode.Conflict, "deployment_cancel_unavailable", "Deployment is no longer in a cancellable state")
                if (!DeploymentWorker.cancel(id)) return@post call.respondError(HttpStatusCode.Conflict, "deployment_cancel_unavailable", "Deployment could not be cancelled because its state changed")
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
                AuditRepository.write(project.id, "deployment_cancelled", actor, "deployment=$id")
                call.respond(ProjectSetupCancelResponse(id.toString()))
            }

            post("/api/admin/projects/{slug}/deployments/{id}/rollback") {
                val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
                val project = ProjectRepository.findBySlug(slug)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_deployment_id", "Invalid deployment ID")
                val item = DeploymentApplicationService.deploymentHistory(project.id, "production").firstOrNull { it.id == id }
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "deployment_not_found", "Deployment not found for project")
                if (!item.canRollback) return@post call.respondError(HttpStatusCode.Conflict, "rollback_unavailable", "Deployment cannot be used as a rollback target")
                val rollbackId = DeploymentWorker.rollback(id)
                    ?: return@post call.respondError(HttpStatusCode.Conflict, "rollback_unavailable", "Deployment cannot be used as a rollback target")
                call.respond(HttpStatusCode.Accepted, ProjectSetupDeployResponse(rollbackId.toString()))
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.setupProjectId(): UUID? {
    val id = parameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (id == null) respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is invalid")
    return id
}

private fun validSetupDeployment(body: CreateDeploymentRequest, repository: String?): Boolean =
    (repository == null || repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) &&
        (repository != null || !body.autoDeploy) &&
        (repository == null || body.gitRef.matches(Regex("^[A-Za-z0-9._/-]+$"))) &&
        body.imageName.matches(Regex("^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")) &&
        body.registry.matches(Regex("^(docker\\.io|[A-Za-z0-9.-]+(:[0-9]{1,5})?)$")) &&
        body.imageTag.matches(Regex("^[A-Za-z0-9_.-]+$")) && body.environment.isNotBlank() && body.containerPort != null && body.containerPort in 1..65535 &&
        (body.env.keys + body.secretEnv.keys).all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) } &&
        body.volumes.all { it.hostPath.isNotBlank() && it.containerPath.isNotBlank() } &&
        (body.hostPort == null || body.hostPort in 1..65535)

private fun DeploymentJobRepository.ConfigurationSummary.toSetupResponse() = ProjectSetupConfigurationResponse(
    id.toString(), repository, gitRef, registry, imageName, imageTag, autoDeploy, containerPort, hostPort, network, restartPolicy,
    environment, env, envKeys, secretSetId?.toString(), secretSetVersion,
    registryCredentialId?.toString()
)

private fun projectOverviewFinancials(
    project: ProjectRepository.ProjectRecord,
    balances: ProjectBalanceAdapter
): Triple<BigDecimal, BigDecimal, BigDecimal> {
    val financials = balances.financials(project)
    return Triple(financials.originalCharge + financials.additionalCharges - financials.discounts, financials.paid, financials.outstanding)
}
