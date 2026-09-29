package com.gatekeeper.nginx

import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.deployment.DeploymentApplicationService
import java.util.UUID

/** Resolves a service's desired gateway target without changing persisted site configuration. */
object DeploymentUpstreamResolver {
    data class Target(val host: String, val port: Int, val containerName: String? = null)
    data class SiteTarget(val mode: UpstreamMode, val host: String, val explicitPort: Int?)
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
        if (runtime == null && site.mode == UpstreamMode.EXPLICIT_PORT) {
            val port = site.explicitPort?.takeIf { it in 1..65535 } ?: return null
            return Target(site.host, port)
        }

        val selectedRuntime = runtime ?: return null
        val containerName = selectedRuntime.containerName?.takeIf(String::isNotBlank) ?: return null
        val containerPort = selectedRuntime.containerPort?.takeIf { it in 1..65535 }
            ?: selectedRuntime.publishedPorts.keys.singleOrNull()?.takeIf { it in 1..65535 }
            ?: return null
        val hostPort = selectedRuntime.publishedPorts[containerPort]?.takeIf { it in 1..65535 } ?: return null
        return Target("127.0.0.1", hostPort, containerName)
    }

    fun resolve(
        serviceId: UUID,
        environment: String,
        site: SiteRepository.SiteRecord,
        activeRuntime: DeploymentApplicationService.ActiveDeploymentRuntime?
    ): Target? = resolve(
        serviceId,
        environment,
        SiteTarget(site.upstreamMode, site.upstreamHost, site.upstreamExplicitPort),
        activeRuntime?.let {
            RuntimeTarget(
                it.serviceId, it.environment,
                it.status in setOf(com.gatekeeper.db.tables.DeploymentStatus.ACTIVE, com.gatekeeper.db.tables.DeploymentStatus.READY),
                it.containerName, it.containerPort, it.publishedPorts
            )
        }
    )

    fun resolve(serviceId: UUID, environment: String): Target? {
        val site = SiteRepository.findByServiceId(serviceId) ?: return null
        val runtime = DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
        return resolve(serviceId, environment, site, runtime)
    }

    /** Compatibility lookup for project-level admin views: resolve its default service. */
    fun resolveProjectDefault(projectId: UUID, environment: String): Target? {
        val site = SiteRepository.findByProjectId(projectId) ?: return null
        val serviceId = site.serviceId ?: return null
        val runtime = DeploymentApplicationService.gatewayDeploymentRuntime(serviceId, environment)
        return resolve(serviceId, environment, site, runtime)
    }
}
