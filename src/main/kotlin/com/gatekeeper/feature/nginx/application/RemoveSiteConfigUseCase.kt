package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.model.isExcluded
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.plugins.DistributedLock
import java.io.File

class RemoveSiteConfigUseCase(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled"
) {
    fun execute(slug: String): Boolean = removeProject(
        slug,
        sitesAvailablePath = sitesAvailablePath,
        sitesEnabledPath = sitesEnabledPath
    )

    fun removeProject(
        slug: String, sitesAvailablePath: String = "/etc/nginx/sites-available",
        sitesEnabledPath: String = "/etc/nginx/sites-enabled"
    ): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") {
            try {
                val availableFile = File("$sitesAvailablePath/$slug")
                val enabledFile = File("$sitesEnabledPath/$slug")
                if (isExcluded(availableFile, enabledFile)) {
                    nginxServiceLogger.warn("Refusing to remove nginx site $slug because it serves Gatekeeperd")
                    return@withLock false
                }

                if (enabledFile.exists()) {
                    enabledFile.delete()
                }
                if (availableFile.exists()) {
                    availableFile.delete()
                }
                File(sitesAvailablePath, ".$slug.gatekeeperd.sha256").delete()

                nginxServiceLogger.info("Removed nginx site: $slug")
                true
            } catch (e: Exception) {
                nginxServiceLogger.error("Failed to remove nginx site: $slug", e)
                false
            }
        }
    }
}
