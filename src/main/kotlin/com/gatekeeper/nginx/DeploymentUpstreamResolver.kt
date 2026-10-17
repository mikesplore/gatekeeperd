package com.gatekeeper.nginx

import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.deployment.DeploymentApplicationService.ActiveDeploymentRuntime
import com.gatekeeper.deployment.PublishedPorts
import java.util.UUID

/** Resolves a service's desired gateway target without changing persisted site configuration. */
object DeploymentUpstreamResolver {
    data class Target(val host: String, val port: Int, val containerName: String? = null, val containerPort: Int? = null)
    data class SiteTarget(
        val mode: UpstreamMode,
        val host: String,
        val explicitPort: Int?,
        val upstreamKind: String? = null,
        val containerPort: Int? = null
    )
    data class RuntimeTarget(
        val serviceId: UUID?, val environment: String, val usable: Boolean,
        val containerName: String?, val containerPort: Int?, val publishedPorts: Map<Int, Int>
    )

    fun resolve(
        serviceId: UUID,
        environment: String,
        site: SiteTarget,
        activeRuntime: RuntimeTarget?
    ): Target? {
        val runtime = activeRuntime?.takeIf {
            it.serviceId == serviceId && it.environment == environment && it.usable
        }
        if (site.mode == UpstreamMode.EXPLICIT_PORT &&
            site.upstreamKind != "DEPLOYMENT_MANAGED" &&
            site.containerPort == null
        ) {
            val port = site.explicitPort?.takeIf { it in 1..65535 } ?: return null
            return Target(site.host, port)
        }

        val selectedRuntime = runtime ?: return null
        val containerName = selectedRuntime.containerName?.takeIf(String::isNotBlank) ?: return null
        val selectedContainerPort = site.containerPort ?: selectedRuntime.containerPort
        val hostPort = PublishedPorts.applicationPort(selectedRuntime.containerPort, selectedRuntime.publishedPorts, selectedContainerPort)
            ?: return null
        return Target("127.0.0.1", hostPort, containerName, selectedContainerPort)
    }

    fun resolve(
        serviceId: UUID,
        environment: String,
        site: SiteRepository.SiteRecord,
        activeRuntime: ActiveDeploymentRuntime?
    ): Target? = resolve(
        serviceId,
        environment,
        SiteTarget(site.upstreamMode, site.upstreamHost, site.upstreamExplicitPort, site.upstreamKind, site.upstreamContainerPort),
        activeRuntime?.let { toRuntimeTarget(it) }
    )

    fun resolve(serviceId: UUID, environment: String): Target? {
        val runtime = DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
        val site = SiteRepository.findByServiceId(serviceId)
        if (site == null) return resolveRuntime(serviceId, environment, runtime)
        return resolve(serviceId, environment, site, runtime)
    }

    /**
     * Port-aware resolution for multi-port services. Each (domain, container port)
     * binding gets its own upstream so a second site never silently routes to the
     * primary port. Falls back to the legacy lookup when no site exists for the port.
     */
    fun resolve(serviceId: UUID, containerPort: Int, environment: String): Target? {
        val runtime = DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
        val site = SiteRepository.findByServiceAndPort(serviceId, containerPort)
            ?: SiteRepository.findByServiceId(serviceId)
        if (site == null) return resolveRuntime(serviceId, containerPort, environment, runtime)
        val siteTarget = SiteTarget(
            site.upstreamMode,
            site.upstreamHost,
            site.upstreamExplicitPort,
            site.upstreamKind,
            site.upstreamContainerPort ?: containerPort,
        )
        return resolve(serviceId, environment, siteTarget, runtime?.let { toRuntimeTarget(it) })
            ?: resolveRuntime(serviceId, containerPort, environment, runtime)
    }

    fun resolveRuntime(serviceId: UUID, environment: String): Target? = resolveRuntime(
        serviceId, environment, DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
    )

    /** Port-aware runtime resolution: host port is derived from the requested container port. */
    fun resolveRuntime(serviceId: UUID, containerPort: Int, environment: String): Target? = resolveRuntime(
        serviceId, containerPort, environment,
        DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
    )

    fun resolveRuntime(serviceId: UUID, environment: String, runtime: RuntimeTarget?): Target? {
        val selected = runtime?.takeIf {
            it.serviceId == serviceId && it.environment == environment && it.usable
        } ?: return null
        val containerName = selected.containerName?.takeIf(String::isNotBlank) ?: return null
        val port = PublishedPorts.applicationPort(selected.containerPort, selected.publishedPorts) ?: return null
        return Target("127.0.0.1", port, containerName, selected.containerPort)
    }

    private fun toRuntimeTarget(runtime: ActiveDeploymentRuntime): RuntimeTarget = RuntimeTarget(
        runtime.serviceId,
        runtime.environment,
        runtime.status in setOf(DeploymentStatus.ACTIVE, DeploymentStatus.READY),
        runtime.containerName,
        runtime.containerPort,
        runtime.publishedPorts,
    )

    private fun resolveRuntime(
        serviceId: UUID,
        environment: String,
        runtime: ActiveDeploymentRuntime?
    ): Target? {
        val selected = runtime?.takeIf {
            it.serviceId == serviceId && it.environment == environment &&
                it.status in setOf(DeploymentStatus.ACTIVE, DeploymentStatus.READY)
        } ?: return null
        return resolveRuntime(serviceId, environment, toRuntimeTarget(selected))
    }

    private fun resolveRuntime(
        serviceId: UUID,
        containerPort: Int,
        environment: String,
        runtime: ActiveDeploymentRuntime?
    ): Target? {
        val selected = runtime?.takeIf {
            it.serviceId == serviceId && it.environment == environment &&
                it.status in setOf(DeploymentStatus.ACTIVE, DeploymentStatus.READY)
        } ?: return null
        val containerName = selected.containerName?.takeIf(String::isNotBlank) ?: return null
        val hostPort = PublishedPorts.applicationPort(selected.containerPort, selected.publishedPorts, containerPort)
            ?: return null
        return Target("127.0.0.1", hostPort, containerName, containerPort)
    }

    /** Compatibility lookup for project-level admin views: resolve its default service. */
    fun resolveProjectDefault(projectId: UUID, environment: String): Target? {
        val site = SiteRepository.findByProjectId(projectId)
        val serviceId = site?.serviceId ?: runCatching { SiteRepository.findDefaultServiceId(projectId) }.getOrNull() ?: return null
        val runtime = DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
        if (site == null) return resolveRuntime(serviceId, environment, runtime)
        return resolve(serviceId, environment, site, runtime)
    }
}
