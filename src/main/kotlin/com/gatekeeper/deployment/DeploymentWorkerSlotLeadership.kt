package com.gatekeeper.deployment

import com.gatekeeper.config.AppSettings

/** Allows only the Gatekeeperd slot currently selected by the stable nginx upstream to run jobs. */
internal class DeploymentWorkerSlotLeadership(
    private val settings: AppSettings.DeploymentWorkerSettings
) {
    fun isActive(): Boolean {
        // Non-slot deployments (local development and legacy single-instance installs) keep
        // their existing worker behavior.
        if (settings.slotName == null) return true

        val activePort = runCatching {
            Regex("(?m)^\\s*server\\s+127\\.0\\.0\\.1:(\\d+)\\s*;")
                .find(settings.activeUpstreamFile.readText())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        }.getOrNull()

        return activePort == settings.listenerPort
    }
}
