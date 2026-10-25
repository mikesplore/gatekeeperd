package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.model.DomainRules

import com.gatekeeper.feature.nginx.presentation.NginxRollbackResponse
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class RollbackSiteConfigUseCase(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val nginxSiteStorage: NginxSiteStorage,
    private val domainRules: DomainRules,
    private val nginxSelfDomainExclusion: NginxSelfDomainExclusion,
    private val applySiteConfigUseCase: ApplySiteConfigUseCase,
) {
    fun execute(slug: String, backupName: String): NginxRollbackResponse = rollbackLocked(slug, backupName)

    fun rollbackLocked(
        slug: String, backupName: String,
    ): NginxRollbackResponse {
        require(backupName.matches(Regex("${Regex.escape(slug)}.bak-[0-9]+"))) { "Invalid backup name" }
        val backup = File(sitesAvailablePath, backupName)
        require(backup.isFile) { "Backup does not exist" }
        val target = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
        require(!domainRules.isExcluded(target, enabledFile) && !nginxSelfDomainExclusion.excludesFile(backup)) {
            "This nginx config serves Gatekeeperd and is excluded from site management"
        }
        domainRules.validateProtectedGateBlocks(slug, backup.readText())
        val stagedFile = File(sitesAvailablePath, ".${slug}.staged")
        Files.copy(backup.toPath(), stagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val validation = nginxSiteStorage.testNginxConfigWithStagedSite(slug, stagedFile)
        if (!validation.valid) {
            Files.deleteIfExists(stagedFile.toPath())
            return NginxRollbackResponse(
                false,
                "Rollback validation failed; previous configuration was preserved",
                validation
            )
        }

        val current = if (target.isFile) {
            File(sitesAvailablePath, "$slug.bak-${Instant.now().toEpochMilli()}").also {
                Files.copy(target.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else null
        try {
            try {
                Files.move(
                    stagedFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(stagedFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            nginxSiteStorage.replaceEnabledSymlink(target, enabledFile)
            if (!nginxSiteStorage.reloadNginx()) {
                nginxSiteStorage.restoreActivatedSite(target, enabledFile, current)
                nginxSiteStorage.reloadNginx()
                return NginxRollbackResponse(
                    false,
                    "Rollback reload failed; previous configuration was restored",
                    validation
                )
            }
        } catch (_: Exception) {
            nginxSiteStorage.restoreActivatedSite(target, enabledFile, current)
            runCatching { nginxSiteStorage.reloadNginx() }
            return NginxRollbackResponse(
                false,
                "Rollback activation failed; previous configuration was restored",
                validation
            )
        } finally {
            Files.deleteIfExists(stagedFile.toPath())
        }
        applySiteConfigUseCase.recordManagedVersion(slug, target.readText())
        return NginxRollbackResponse(
            true,
            "Configuration rolled back and Nginx reloaded successfully",
            validation,
            true
        )
    }
}
