package com.gatekeeper.feature.nginx.domain.port

import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamIncludes
import com.gatekeeper.feature.nginx.presentation.NginxBackup
import com.gatekeeper.feature.nginx.presentation.NginxManualConfigDisableResult
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import java.io.File

interface NginxSiteStorage {
    fun readAvailable(slug: String): String?
    fun writeAvailable(slug: String, content: String): File
    fun enableSite(slug: String): Boolean
    fun disableSite(slug: String): Boolean
    fun siteExists(slug: String): Boolean
    fun isSiteEnabled(slug: String): Boolean
    fun removeSite(slug: String): Boolean
    fun createBackup(slug: String, content: String): File
    fun restoreBackup(slug: String, backupName: String): Boolean
    fun writeUpstreamInclude(serviceId: java.util.UUID, content: String): File
    fun readUpstreamInclude(serviceId: java.util.UUID): String?
    fun listManualConfigs(): List<File>
    fun readManualConfig(filename: String): String?
    fun disableManualConfig(filename: String): NginxManualConfigDisableResult
    fun deleteDisabledManualConfig(filename: String): String
    fun testNginxConfig(): Boolean
    fun reloadNginx(): Boolean
    fun removeSiteArtifacts(site: SiteRecord): Boolean
    fun nginxIncludesDirectoryInHttpContext(configDump: String, directory: String): Boolean
    fun testNginxConfigWithStagedSite(slug: String, stagedFile: File): NginxTestResult
    fun includePort(content: String): Int?
    fun restoreUpstreamInclude(
        includes: DeploymentUpstreamIncludes,
        serviceId: java.util.UUID,
        previous: String?
    )
    fun replaceEnabledSymlink(availableFile: File, enabledFile: File)
    fun restoreActivatedSite(availableFile: File, enabledFile: File, backup: File?)
    fun listConfigFiles(directory: File): List<File>
    fun restoreSiteConfiguration(slug: String, previousConfig: String): Boolean
    fun listBackups(slug: String): List<NginxBackup>
    fun testNginxConfigDetailed(): NginxTestResult
}