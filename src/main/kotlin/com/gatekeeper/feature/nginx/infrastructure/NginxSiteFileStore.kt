package com.gatekeeper.feature.nginx.infrastructure

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.DisableManualConfigUseCase
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.nginx.presentation.NginxBackup
import com.gatekeeper.feature.nginx.presentation.NginxManualConfigDisableResult
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.nginx.*
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class NginxSiteFileStore(
    private val settings: AppSettings.NginxSettings,
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val nginxDomainRules: DomainRules,
    private val nginxTestRunner: () -> NginxTestResult = { NginxCommandRunner().test() },
    private val nginxReloadRunner: () -> Boolean = { NginxCommandRunner().reload() },
    private val removeSiteConfigUseCase: Lazy<RemoveSiteConfigUseCase>,
    private val listConfigArtifacts: Lazy<ListConfigArtifactsUseCase>,
    private val applySiteConfigUseCase: Lazy<ApplySiteConfigUseCase>,
    private val disableManConfig: Lazy<DisableManualConfigUseCase>

    ) : NginxSiteStorage {
    private val availableDir = File(settings.sitesAvailablePath)
    private val enabledDir = File(settings.sitesEnabledPath)
    private val upstreamDir = File(settings.upstreamIncludeDirectory)

    override fun readAvailable(slug: String): String? =
        File(availableDir, slug).takeIf { it.exists() }?.readText()

    override fun writeAvailable(slug: String, content: String): File {
        availableDir.mkdirs()
        val file = File(availableDir, slug)
        file.writeText(content)
        return file
    }

    override fun enableSite(slug: String): Boolean {
        enabledDir.mkdirs()
        val target = File(availableDir, slug)
        val link = File(enabledDir, slug)
        if (!target.exists()) return false
        if (link.exists()) link.delete()
        return try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun disableSite(slug: String): Boolean {
        val link = File(enabledDir, slug)
        return if (link.exists()) link.delete() else true
    }

    override fun siteExists(slug: String): Boolean = File(availableDir, slug).exists()
    override fun isSiteEnabled(slug: String): Boolean = File(enabledDir, slug).exists()

    override fun removeSite(slug: String): Boolean {
        disableSite(slug)
        return File(availableDir, slug).delete()
    }

    override fun createBackup(slug: String, content: String): File {
        val backupDir = File(availableDir, ".backups/$slug").apply { mkdirs() }
        val backupFile = File(backupDir, "${System.currentTimeMillis()}.conf")
        backupFile.writeText(content)
        return backupFile
    }

//    override fun listBackups(slug: String): List<NginxBackup> {
//        val backupDir = File(availableDir, ".backups/$slug")
//        if (!backupDir.exists()) return emptyList()
//        return backupDir.listFiles { file -> file.isFile && file.extension == "conf" }
//            ?.sortedByDescending { it.lastModified() }
//            ?.map { NginxBackup(name = it.name, timestamp = it.lastModified(), path = it.absolutePath) }
//            ?: emptyList()
//    }

    override fun restoreBackup(slug: String, backupName: String): Boolean {
        val backupFile = File(availableDir, ".backups/$slug/$backupName")
        if (!backupFile.exists()) return false
        writeAvailable(slug, backupFile.readText())
        return true
    }

    override fun writeUpstreamInclude(serviceId: java.util.UUID, content: String): File {
        upstreamDir.mkdirs()
        val file = File(upstreamDir, "$serviceId.conf")
        file.writeText(content)
        return file
    }

    override fun readUpstreamInclude(serviceId: java.util.UUID): String? =
        File(upstreamDir, "$serviceId.conf").takeIf { it.exists() }?.readText()

    override fun listManualConfigs(): List<File> =
        availableDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.toList() ?: emptyList()

    override fun readManualConfig(filename: String): String? =
        File(availableDir, filename).takeIf { it.exists() }?.readText()

    override fun disableManualConfig(filename: String): NginxManualConfigDisableResult {
        disableSite(filename)
        return disableManConfig.value.execute(filename = filename)
    }

    override fun deleteDisabledManualConfig(filename: String): String {
        File(availableDir, filename).delete()
        return filename
    }

    override fun testNginxConfig(): Boolean {
        return testNginxConfigDetailed().valid
    }

    override fun reloadNginx(): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") {
            try {
                val testPassed = testNginxConfig()
                if (!testPassed) return@withLock false
                if (!nginxReloadRunner()) return@withLock false
                nginxServiceLogger.info("Nginx reloaded successfully")
                true
            } catch (e: Exception) {
                nginxServiceLogger.error("Failed to reload nginx", e)
                false
            }
        }
    }

    override fun nginxIncludesDirectoryInHttpContext(configDump: String, directory: String): Boolean {
        val expected = "${File(directory).absolutePath.trimEnd('/')}/*.conf"
        val withoutComments = configDump.lineSequence().joinToString("\n") { it.substringBefore('#') }
        val httpStart = Regex("\\bhttp\\s*\\{").find(withoutComments) ?: return false
        var depth = 1
        var index = httpStart.range.last + 1
        val bodyStart = index
        while (index < withoutComments.length && depth > 0) {
            when (withoutComments[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 1 && withoutComments[index] == ';') {
                val statement = withoutComments.substring(bodyStart.coerceAtMost(index), index + 1)
                if (Regex("\\binclude\\s+${Regex.escape(expected)}\\s*;").containsMatchIn(statement)) return true
            }
            index++
        }
        return false
    }

    override fun testNginxConfigWithStagedSite(slug: String, stagedFile: File): NginxTestResult {
        // Keep the name visible to nginx's usual `sites-enabled/*` include glob.
        val stagedLink = File(sitesEnabledPath, "gatekeeperd-staged-$slug-${System.nanoTime()}")
        return try {
            Files.createSymbolicLink(stagedLink.toPath(), stagedFile.toPath())
            testNginxConfigDetailed()
        } finally {
            Files.deleteIfExists(stagedLink.toPath())
        }
    }

   override fun includePort(content: String): Int? = Regex("server\\s+127\\.0\\.0\\.1:(\\d+);?")
        .find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }

    override fun restoreUpstreamInclude(
        includes: DeploymentUpstreamIncludes,
        serviceId: java.util.UUID,
        previous: String?
    ) {
        if (previous == null) Files.deleteIfExists(includes.file(serviceId).toPath()) else includes.write(
            serviceId,
            previous
        )
        check(nginxTestRunner().valid && nginxReloadRunner()) { "Unable to restore the previous nginx upstream include" }
    }

    override fun replaceEnabledSymlink(availableFile: File, enabledFile: File) {
        Files.deleteIfExists(enabledFile.toPath())
        Files.createSymbolicLink(enabledFile.toPath(), availableFile.toPath())
    }

    override fun restoreActivatedSite(availableFile: File, enabledFile: File, backup: File?) {
        Files.deleteIfExists(enabledFile.toPath())
        if (backup?.isFile == true) {
            Files.copy(backup.toPath(), availableFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            replaceEnabledSymlink(availableFile, enabledFile)
        } else {
            Files.deleteIfExists(availableFile.toPath())
        }
    }

    override fun listConfigFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Nginx config path '${directory.absolutePath}' is not a directory" }
        return directory.listFiles()?.toList()
            ?: error("Unable to list nginx config directory '${directory.absolutePath}'")
    }

    override fun restoreSiteConfiguration(slug: String, previousConfig: String): Boolean =
        applySiteConfigUseCase.value.enableProject(slug, previousConfig)


    override fun removeSiteArtifacts(site: SiteRecord): Boolean {
        val matches = listConfigArtifacts.value.execute(listOf(site)).filter { artifact ->
            artifact.siteId == site.id.toString() ||
                    artifact.projectId == site.projectId.toString() && (
                    artifact.serviceId == site.serviceId?.toString() ||
                            artifact.serviceId == null && artifact.domains.any {
                        it.equals(
                            site.domain,
                            ignoreCase = true
                        )
                    }
                    )
        }.map { it.filename }.toMutableSet()
        site.projectSlug?.let(matches::add)
        return matches.all(removeSiteConfigUseCase.value::execute)
    }

   override fun listBackups(slug: String): List<NginxBackup> {
        require(slug.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid nginx site name" }
        require(
            !nginxDomainRules.isExcluded(
                File(sitesAvailablePath, slug),
                File(sitesEnabledPath, slug)
            )
        ) { "This nginx config serves Gatekeeperd and is excluded from site management" }
        return File(sitesAvailablePath).listFiles { file -> file.name.startsWith("$slug.bak-") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { NginxBackup(it.name, Instant.ofEpochMilli(it.lastModified()).toString(), it.length()) }
            .orEmpty()
    }

    override fun testNginxConfigDetailed(): NginxTestResult {
        return nginxTestRunner()
    }

}