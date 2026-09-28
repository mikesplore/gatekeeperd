package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.Deployments
import com.gatekeeper.db.tables.DeploymentExecutions
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.docker.DockerService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.DeploymentUpstreamResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@Serializable
data class DeploymentDriftItem(val code: String, val detail: String)

@Serializable
data class ActiveDeploymentReconciliation(
    val projectId: String,
    val projectSlug: String?,
    val environment: String,
    val deploymentId: String,
    val containerName: String?,
    val expectedPorts: Map<Int, Int>,
    val observedContainerState: String? = null,
    val observedPorts: String? = null,
    val gatewayTarget: String? = null,
    val drift: List<DeploymentDriftItem>
)

@Serializable
data class DeploymentReconciliationReport(
    val observedAt: String,
    val status: String,
    val entries: List<ActiveDeploymentReconciliation>,
    val errors: List<String>,
    val actionsTaken: List<String> = emptyList()
)

/** Read-only comparison of persisted active deployments with Docker and managed nginx state. */
class DeploymentReconciliationService(
    private val docker: DockerService?,
    private val nginx: NginxService
) {
    fun report(): DeploymentReconciliationReport {
        val errors = mutableListOf<String>()
        val observations = readActiveDeployments(errors)
        val entries = observations.map { deployment -> observe(deployment, errors) }
        return DeploymentReconciliationReport(
            observedAt = java.time.Instant.now().toString(),
            status = if (errors.isEmpty()) "observed" else "partial",
            entries = entries,
            errors = errors.distinct(),
            actionsTaken = emptyList()
        )
    }

    private data class ActiveDeployment(
        val id: UUID,
        val projectId: UUID,
        val serviceId: UUID?,
        val slug: String?,
        val environment: String,
        val containerName: String?,
        val hostPort: Int?,
        val ports: Map<Int, Int>
    )

    private fun readActiveDeployments(errors: MutableList<String>): List<ActiveDeployment> = try {
        transaction {
            Deployments.selectAll().where { Deployments.status eq DeploymentStatus.ACTIVE }
                .map { row ->
                    val execution = DeploymentExecutions.selectAll()
                        .where { DeploymentExecutions.id eq row[Deployments.executionId] }.singleOrNull()
                    val projectId = row[Deployments.projectId] ?: error("Active deployment has no project_id")
                    val project = run {
                        Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()
                    }
                    val rawPorts = row[Deployments.runtimePortsJson]
                    val ports = runCatching { Json.decodeFromString<Map<String, Int>>(rawPorts).mapKeys { it.key.toInt() } }
                        .getOrElse {
                            errors += "deployment ${row[Deployments.id]} has invalid persisted runtime_ports_json"
                            emptyMap()
                        }
                    ActiveDeployment(
                        id = row[Deployments.id], projectId = projectId,
                        serviceId = row[Deployments.serviceId],
                        slug = project?.get(Projects.slug),
                        environment = row[Deployments.environment], containerName = row[Deployments.runtimeContainerName],
                        hostPort = row[Deployments.runtimeHostPort], ports = ports
                    )
                }
        }
    } catch (error: Exception) {
        errors += "unable to read persisted active deployments: ${error.message ?: error.javaClass.simpleName}"
        emptyList()
    }

    private fun observe(deployment: ActiveDeployment, errors: MutableList<String>): ActiveDeploymentReconciliation {
        val drift = mutableListOf<DeploymentDriftItem>()
        var observedState: String? = null
        var observedPorts: String? = null
        var gatewayTarget: String? = null
        val slug = deployment.slug

        val containerName = deployment.containerName
        if (containerName.isNullOrBlank()) {
            drift += DeploymentDriftItem("runtime_identity_missing", "Active deployment has no persisted runtime container name")
        } else if (docker == null) {
            errors += "Docker unavailable while inspecting deployment ${deployment.id}"
        } else {
            val dockerClient = docker
            val container = runCatching { dockerClient.getContainer(containerName) }.getOrElse {
                errors += "Docker inspection failed for deployment ${deployment.id}: ${it.message ?: it.javaClass.simpleName}"
                null
            }
            if (container == null) {
                drift += DeploymentDriftItem("runtime_missing", "Persisted runtime '$containerName' was not found")
            } else {
                observedState = container.state
                observedPorts = container.ports
                if (!container.state.equals("running", ignoreCase = true)) {
                    drift += DeploymentDriftItem("runtime_not_running", "Persisted runtime '$containerName' is ${container.state}")
                }
                deployment.ports.forEach { (containerPort, expectedHostPort) ->
                    if (!container.ports.split(',').any { mapping ->
                            val parsed = Regex("(?:^|,\\s*)(\\d+):(\\d+)/tcp").find(mapping.trim())
                            parsed?.groupValues?.get(1)?.toIntOrNull() == expectedHostPort && parsed.groupValues[2].toIntOrNull() == containerPort
                        }) {
                        drift += DeploymentDriftItem("runtime_port_mismatch", "Expected container port $containerPort on host port $expectedHostPort; observed ${container.ports.ifBlank { "no published ports" }}")
                    }
                }
                if (deployment.ports.isEmpty() && deployment.hostPort != null && !container.ports.contains("${deployment.hostPort}:")) {
                    drift += DeploymentDriftItem("runtime_port_mismatch", "Expected host port ${deployment.hostPort}; observed ${container.ports.ifBlank { "no published ports" }}")
                }
            }
        }

        val project = runCatching { ProjectRepository.findById(deployment.projectId) }.getOrElse {
            errors += "project lookup failed for deployment ${deployment.id}: ${it.message ?: it.javaClass.simpleName}"
            null
        }
        if (project == null) {
            drift += DeploymentDriftItem("project_missing", "Persisted project_id ${deployment.projectId} has no project row")
        }

        val site = runCatching { deployment.serviceId?.let(SiteRepository::findByServiceId) }.getOrElse {
                errors += "gateway site lookup failed for deployment ${deployment.id}: ${it.message ?: it.javaClass.simpleName}"
                null
            }
        if (site == null) {
                drift += DeploymentDriftItem("gateway_site_missing", "Project has no persisted gateway site")
        } else {
                gatewayTarget = when (site.upstreamMode) {
                    UpstreamMode.EXPLICIT_PORT -> "${site.upstreamHost}:${site.upstreamExplicitPort ?: "missing-port"}"
                    UpstreamMode.DOCKER_DISCOVERY -> site.serviceId?.let { DeploymentUpstreamResolver.resolve(it, deployment.environment) }?.let { "${it.host}:${it.port}" } ?: "missing-runtime"
                }
                val siteSlug = site.projectSlug ?: slug ?: project?.slug ?: run {
                    drift += DeploymentDriftItem("project_slug_missing", "Project slug is unavailable for managed gateway inspection")
                    return@observe ActiveDeploymentReconciliation(
                        projectId = deployment.projectId.toString(), projectSlug = null, environment = deployment.environment,
                        deploymentId = deployment.id.toString(), containerName = containerName, expectedPorts = deployment.ports,
                        observedContainerState = observedState, observedPorts = observedPorts, gatewayTarget = gatewayTarget,
                        drift = drift.distinctBy { it.code to it.detail }
                    )
                }
                val inspection = runCatching { nginx.inspectSite(siteSlug) }.getOrElse {
                    errors += "nginx inspection failed for project ${deployment.projectId}: ${it.message ?: it.javaClass.simpleName}"
                    null
                }
                if (inspection == null || !inspection.available) {
                    drift += DeploymentDriftItem("gateway_config_missing", "Managed nginx configuration '$siteSlug' is missing")
                } else if (!inspection.enabled) {
                    drift += DeploymentDriftItem("gateway_config_disabled", "Managed nginx configuration '$siteSlug' is not enabled")
                } else if (!inspection.managed) {
                    drift += DeploymentDriftItem("gateway_config_unmanaged", "Nginx configuration '$siteSlug' has no Gatekeeperd managed marker")
                } else {
                    val configuredTarget = Regex("(?m)^\\s*proxy_pass\\s+https?://([^;]+);")
                        .findAll(inspection.content.orEmpty()).map { it.groupValues[1].trim() }
                        .firstOrNull { it.substringBefore('/') != "127.0.0.1:8080" }
                    val expectedTarget = when (site.upstreamMode) {
                        UpstreamMode.EXPLICIT_PORT -> site.upstreamExplicitPort?.let { "${site.upstreamHost}:$it" }
                        UpstreamMode.DOCKER_DISCOVERY -> site.serviceId?.let { DeploymentUpstreamResolver.resolve(it, deployment.environment) }?.let { "${it.host}:${it.port}" }
                    }
                    if (configuredTarget == null || expectedTarget == null || configuredTarget != expectedTarget) {
                        drift += DeploymentDriftItem("gateway_config_drift", "Persisted gateway target '$expectedTarget' does not match nginx target '${configuredTarget ?: "missing"}'")
                    }
                    if (deployment.environment == "production" && containerName != null &&
                        site.upstreamMode == UpstreamMode.EXPLICIT_PORT && site.upstreamHost == "127.0.0.1") {
                        val expectedHostPort = deployment.hostPort ?: deployment.ports.values.singleOrNull()
                        if (expectedHostPort != null && site.upstreamExplicitPort != expectedHostPort) {
                            drift += DeploymentDriftItem("gateway_active_runtime_mismatch", "Gateway targets ${site.upstreamHost}:${site.upstreamExplicitPort}; active deployment runtime is published on $expectedHostPort")
                        }
                    }
                    val resolvedDockerTarget = if (site.upstreamMode == UpstreamMode.DOCKER_DISCOVERY) site.serviceId?.let { DeploymentUpstreamResolver.resolve(it, deployment.environment) } else null
                    if (deployment.environment == "production" && site.upstreamMode == UpstreamMode.DOCKER_DISCOVERY && resolvedDockerTarget?.containerName != containerName) {
                        drift += DeploymentDriftItem("gateway_active_runtime_mismatch", "Gateway discovery target '${resolvedDockerTarget?.containerName ?: "missing"}' does not match active deployment runtime '$containerName'")
                    }
                }
        }

        return ActiveDeploymentReconciliation(
            projectId = deployment.projectId.toString(), projectSlug = slug, environment = deployment.environment,
            deploymentId = deployment.id.toString(), containerName = containerName, expectedPorts = deployment.ports,
            observedContainerState = observedState, observedPorts = observedPorts, gatewayTarget = gatewayTarget,
            drift = drift.distinctBy { it.code to it.detail }
        )
    }

    companion object {
        fun production(dockerSocket: String): DeploymentReconciliationService {
            val docker = runCatching { DockerService(dockerSocket) }.getOrNull()
            return DeploymentReconciliationService(docker, NginxService())
        }
    }
}
