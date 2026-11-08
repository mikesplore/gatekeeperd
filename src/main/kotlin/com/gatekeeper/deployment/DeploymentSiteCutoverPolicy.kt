package com.gatekeeper.deployment

import java.util.UUID

/** Cutover rules for sites that receive Gatekeeperd-managed deployments. */
internal object DeploymentSiteCutoverPolicy {
    fun hasDeploymentRoute(upstreamKind: String?): Boolean =
        upstreamKind == "DEPLOYMENT_MANAGED" || upstreamKind == "EXPLICIT_PORT"

    fun referencesServiceUpstream(config: String?, serviceId: UUID): Boolean {
        val upstreamName = "gk_${serviceId.toString().replace("-", "")}"
        return config?.lineSequence()?.any { line ->
            line.trim() == "proxy_pass http://$upstreamName;"
        } == true
    }

    fun convertsLegacySite(upstreamKind: String?, referencesStableUpstream: Boolean = false): Boolean =
        upstreamKind == "EXPLICIT_PORT" || (referencesStableUpstream && upstreamKind != "DEPLOYMENT_MANAGED")

    fun primaryContainerPort(configuredPort: Int?, upstreamKind: String?, explicitUpstreamPort: Int?): Int? =
        configuredPort ?: explicitUpstreamPort.takeIf { hasDeploymentRoute(upstreamKind) }
}
