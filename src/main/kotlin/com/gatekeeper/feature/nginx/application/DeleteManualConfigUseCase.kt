package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.model.DomainRules
import java.io.File
import java.nio.file.Files
import java.time.Instant

class DeleteManualConfigUseCase(
    private val siteStorage: NginxSiteStorage,
    private val domainRules: DomainRules,
    private val inspectManualConfig: InspectManualConfigUseCase,
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
) {
    fun execute(filename: String): String = deleteDisabledManualConfigLocked(
        filename
    )

    fun deleteDisabledManualConfigLocked(
        filename: String
    ): String {
        require(domainRules.isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val detail = inspectManualConfig.inspectManualConfig(filename)
        require(!detail.enabled) { "Disable the manual nginx config before deleting it" }
        val available = File(sitesAvailablePath, filename)
        require(available.isFile) { "Manual nginx config is not present in sites-available" }

        val backup = File(available.parentFile, "$filename.bak-${Instant.now().toEpochMilli()}")
        Files.copy(available.toPath(), backup.toPath())
        Files.delete(available.toPath())
        val validation = siteStorage.testNginxConfigDetailed()
        if (!validation.valid) {
            Files.copy(backup.toPath(), available.toPath())
            throw IllegalStateException("nginx -t failed after deleting '$filename'; the config was restored from backup: ${validation.output}")
        }
        return backup.name
    }
}