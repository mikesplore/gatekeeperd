package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.plugins.DistributedLock
import java.io.File

class DisableProjectUseCase(
    private val domainRules: DomainRules,
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled"
) {
    fun execute(slug: String): Boolean = disableProject(slug)

    fun disableProject(
        slug: String
    ): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") {
            try {
                val enabledFile = File("$sitesEnabledPath/$slug")
                if (domainRules.isExcluded(File(sitesAvailablePath, slug), enabledFile)) {
                    nginxServiceLogger.warn("Refusing to disable nginx site $slug because it serves Gatekeeperd")
                    return@withLock false
                }
                if (enabledFile.exists()) {
                    enabledFile.delete()
                    nginxServiceLogger.info("Disabled nginx site: $slug")
                }
                true
            } catch (e: Exception) {
                nginxServiceLogger.error("Failed to disable nginx site: $slug", e)
                false
            }
        }
    }
}
