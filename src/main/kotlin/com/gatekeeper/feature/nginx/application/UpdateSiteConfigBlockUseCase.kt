package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.presentation.NginxBlockUpdateResponse
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class UpdateSiteConfigBlockUseCase(
    private val nginxSiteStorage: NginxSiteStorage
) {
    fun execute(
        slug: String, blockIndex: Int, replacement: String,
        siteRepository: SiteRepository
    ): NginxBlockUpdateResponse {
        return DistributedLock.withLock("nginx-host-mutation") {
            applyBlockUpdateLocked(slug, blockIndex, replacement, siteRepository, nginxSiteStorage = nginxSiteStorage)
        }
    }

    fun applyBlockUpdateLocked(
        slug: String, blockIndex: Int,
        replacement: String,
        siteRepository: SiteRepository,
        sitesAvailablePath: String = "/etc/nginx/sites-available",
        nginxSiteStorage: NginxSiteStorage
    ): NginxBlockUpdateResponse {
        val file = File(sitesAvailablePath, slug)
        val proposed = previewBlockUpdate(slug, blockIndex, replacement, siteRepository)
        val current = file.readText()
        val backup = File(file.parentFile, "$slug.bak-${Instant.now().toEpochMilli()}")
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(file.toPath(), proposed)
        val validation = nginxSiteStorage.testNginxConfigDetailed()
        if (!validation.valid) {
            Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return NginxBlockUpdateResponse(
                false,
                "Nginx validation failed; previous configuration restored",
                current,
                blockIndex,
                validation
            )
        }
        if (!nginxSiteStorage.reloadNginx()) {
            Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            nginxSiteStorage.reloadNginx()
            return NginxBlockUpdateResponse(
                false,
                "Nginx reload failed; previous configuration restored",
                current,
                blockIndex,
                validation
            )
        }
        nginxSiteStorage.recordManagedVersion(slug, proposed)
        return NginxBlockUpdateResponse(
            true,
            "Nginx block updated and reloaded successfully",
            proposed,
            blockIndex,
            validation,
            true
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun previewBlockUpdate(slug: String, blockIndex: Int, replacement: String, siteRepository: SiteRepository): String {
        val current = inspectSite(siteRepository = siteRepository, slug = slug)
        require(current.available) { "Nginx site '$slug' does not exist" }
        require(!current.manual) { "Nginx site '$slug' is manual/unmanaged; edit the file directly. Gatekeeperd will only detect drift." }
        require(blockIndex in current.blocks.indices) { "Block index $blockIndex is out of range" }
        throw IllegalArgumentException("Managed Nginx blocks are read-only. Add or remove custom location blocks through the custom locations API.")
    }
}
