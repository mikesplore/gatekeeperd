package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.model.domainsOverlap
import com.gatekeeper.feature.nginx.model.isSafeConfigFilename
import com.gatekeeper.feature.nginx.model.portsOverlap
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import com.gatekeeper.feature.nginx.presentation.NginxManualConfigDisableResult
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import java.nio.file.Files
import java.time.Instant

class DisableManualConfigUseCase(
    private val nginxSiteStorage: NginxSiteStorage
) {
    fun execute(filename: String): NginxManualConfigDisableResult = disableManualConfigLocked(filename)

    fun disableManualConfigLocked(
        filename: String, sitesAvailablePath: String = "/etc/nginx/sites-available",
        sitesEnabledPath: String = "/etc/nginx/sites-enabled",
        nginxSiteStorage: NginxSiteStorage,
    ): NginxManualConfigDisableResult {
        require(isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val detail = inspectManualConfig(filename)
        require(detail.enabled) { "Manual nginx config is already disabled" }
        val available = File(sitesAvailablePath, filename)
        val availableWasPresent = available.isFile
        val enabled = File(sitesEnabledPath, filename)
        val wasSymlink = Files.isSymbolicLink(enabled.toPath())
        val linkTarget = if (wasSymlink) Files.readSymbolicLink(enabled.toPath()) else null
        val backup = File(available.parentFile, "$filename.bak-${Instant.now().toEpochMilli()}")
        if (!availableWasPresent) Files.writeString(available.toPath(), detail.content)
        Files.copy(available.toPath(), backup.toPath())
        Files.deleteIfExists(enabled.toPath())

        val validation = nginxSiteStorage.testNginxConfigDetailed()
        if (!validation.valid) {
            restoreEnabledManualConfig(available, enabled, wasSymlink, linkTarget)
            if (!availableWasPresent) Files.deleteIfExists(available.toPath())
            throw IllegalStateException("nginx -t failed after disabling '$filename'; the enabled config was restored: ${validation.output}")
        }
        if (!nginxReloadRunner()) {
            restoreEnabledManualConfig(available, enabled, wasSymlink, linkTarget)
            if (!availableWasPresent) Files.deleteIfExists(available.toPath())
            runCatching { nginxSiteStorage.reloadNginx() }
            throw IllegalStateException("Nginx reload failed after disabling '$filename'; the enabled config was restored")
        }

        val afterDisable = listConfigArtifacts()
        val matchingManaged = afterDisable.filter { artifact ->
            artifact.classification == NginxConfigClassification.GATEKEEPER_MANAGED && artifact.tracked && artifact.enabled &&
                    domainsOverlap(detail.domains, artifact.domains) && portsOverlap(
                detail.listenPorts,
                artifact.listenPorts
            )
        }
        if (matchingManaged.isNotEmpty()) {
            val competing = afterDisable.filter { artifact ->
                artifact.filename != filename && artifact.enabled &&
                        domainsOverlap(detail.domains, artifact.domains) && portsOverlap(
                    detail.listenPorts,
                    artifact.listenPorts
                ) &&
                        !(artifact.classification == NginxConfigClassification.GATEKEEPER_MANAGED && artifact.tracked && matchingManaged.any { it.filename == artifact.filename })
            }
            if (competing.isNotEmpty()) {
                restoreEnabledManualConfig(available, enabled, wasSymlink, linkTarget)
                if (!availableWasPresent) Files.deleteIfExists(available.toPath())
                runCatching { nginxSiteStorage.reloadNginx() }
                throw IllegalStateException("The domain also matches enabled configs (${competing.joinToString { it.filename }}); the manual config was restored because traffic could not be verified to reach the intended Gatekeeper-managed site")
            }
        }
        return NginxManualConfigDisableResult(backup.name, matchingManaged.map { it.filename })
    }
}
