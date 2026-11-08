package com.gatekeeper.feature.nginx.application

import com.gatekeeper.core.util.sha256
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigParser
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamIncludes
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.presentation.NginxConfigInspection
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.collections.orEmpty
import kotlin.text.Regex
import kotlin.text.contains
import kotlin.text.matches
import kotlin.text.trim
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy

class InspectSiteConfigUseCase(
    private val siteRepository: SiteRepository,
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams",
    private val domainRules: DomainRules,
    private val nginx: NginxSiteStorage,
    private val nginxConfigDumpRunner: () -> String?,
    private val parser: NginxConfigParser,
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled"
) {
    fun resolveSiteConfigSlug(site: SiteRecord): String =
        SiteConfigIdentityResolver(sitesAvailablePath, sitesEnabledPath).resolve(site)

    fun inspectSite(
        slug: String,
        sitesAvailablePath: String = this.sitesAvailablePath,
        sitesEnabledPath: String = this.sitesEnabledPath
    ): NginxConfigInspection {
        require(slug.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid nginx site name" }
        val availableFile = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
        require(
            !domainRules.isExcluded(
                availableFile,
                enabledFile
            )
        ) { "This nginx config serves Gatekeeperd and is excluded from site management" }
        val content = availableFile.takeIf { it.isFile }?.readText()
        val actualHash = content?.let(::sha256)
        val hashFile = File(sitesAvailablePath, ".$slug.gatekeeperd.sha256")
        val managedHash = hashFile.takeIf { it.isFile }?.readText()?.trim()
        return NginxConfigInspection(
            slug = slug,
            configPath = availableFile.absolutePath,
            enabledPath = enabledFile.absolutePath,
            available = availableFile.isFile,
            enabled = enabledFile.exists(),
            isSymlink = Files.isSymbolicLink(enabledFile.toPath()),
            content = content,
            blocks = content?.let(parser::extractBlocks).orEmpty(),
            customLocations = runCatching { siteRepository.findByProjectSlug(slug)?.customNginxLocations.orEmpty() }.getOrDefault(
                emptyList()
            ),
            modifiedAt = availableFile.takeIf { it.exists() }
                ?.let { Instant.ofEpochMilli(it.lastModified()).toString() },
            sizeBytes = availableFile.takeIf { it.exists() }?.length(),
            managed = content?.contains("# gatekeeperd:block:") == true,
            manual = content != null && !content.contains("# gatekeeperd:block:"),
            drifted = managedHash != null && actualHash != managedHash,
            actualSha256 = actualHash,
            managedSha256 = managedHash
        )
    }

    fun bootstrapDeploymentUpstreams(upstreamIncludeDirectory: String = this.upstreamIncludeDirectory) {
        val directory = File(upstreamIncludeDirectory).absoluteFile
        check((directory.isDirectory || directory.mkdirs()) && directory.canWrite()) {
            "Cannot create or write nginx deployment upstream include directory: ${directory.absolutePath}"
        }
        val dump = nginxConfigDumpRunner()
            ?: error("Unable to read active nginx configuration with nginx -T; deployment upstream bootstrap cannot continue")
        check(nginx.nginxIncludesDirectoryInHttpContext(dump, directory.path)) {
            "nginx.conf must include ${directory.path}/*.conf inside the http {} block before Gatekeeperd deployment cutovers can start"
        }
    }

    fun inspectDeploymentUpstreamPort(serviceId: java.util.UUID): Int? = runCatching {
        val content =
            DeploymentUpstreamIncludes(File(upstreamIncludeDirectory)).file(serviceId).takeIf { it.isFile }?.readText()
                ?: return null
        Regex("""(?m)^\s*server\s+127\.0\.0\.1:(\d+)\s*;""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()
}
