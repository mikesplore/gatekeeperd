package com.gatekeeper.deployment

/** Cutover rules for sites that receive Gatekeeperd-managed deployments. */
internal object DeploymentSiteCutoverPolicy {
    fun hasDeploymentRoute(upstreamKind: String?): Boolean =
        upstreamKind == "DEPLOYMENT_MANAGED" || upstreamKind == "EXPLICIT_PORT"

    fun convertsLegacySite(upstreamKind: String?): Boolean = upstreamKind == "EXPLICIT_PORT"

    fun primaryContainerPort(configuredPort: Int?, upstreamKind: String?, explicitUpstreamPort: Int?): Int? =
        configuredPort ?: explicitUpstreamPort.takeIf { hasDeploymentRoute(upstreamKind) }
}
