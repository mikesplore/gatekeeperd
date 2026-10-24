package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import java.nio.file.Files
import java.time.Instant

class DeleteManualConfigUseCase(
    private val siteStorage: NginxSiteStorage,
    private val domainRules: DomainRules
) {
    fun execute(filename: String): String = deleteDisabledManualConfig(
        filename,
        siteStorage,
        domainRules
    )

    fun deleteDisabledManualConfig(
        filename: String,
        siteStorage: NginxSiteStorage,
        domainRules: DomainRules
    ): String = deleteDisabledManualConfigLocked(
        filename = filename,
        nginxSiteStorage = siteStorage,
        domainRules = domainRules
    )

    fun deleteDisabledManualConfigLocked(
        filename: String,
        sitesAvailablePath: String = "/etc/nginx/sites-available",
        nginxSiteStorage: NginxSiteStorage,
        domainRules: DomainRules
    ): String {
        require(domainRules.isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val detail = inspectManualConfig(filename)
        require(!detail.enabled) { "Disable the manual nginx config before deleting it" }
        val available = File(sitesAvailablePath, filename)
        require(available.isFile) { "Manual nginx config is not present in sites-available" }

        val backup = File(available.parentFile, "$filename.bak-${Instant.now().toEpochMilli()}")
        Files.copy(available.toPath(), backup.toPath())
        Files.delete(available.toPath())
        val validation = nginxSiteStorage.testNginxConfigDetailed()
        if (!validation.valid) {
            Files.copy(backup.toPath(), available.toPath())
            throw IllegalStateException("nginx -t failed after deleting '$filename'; the config was restored from backup: ${validation.output}")
        }
        return backup.name
    }
}