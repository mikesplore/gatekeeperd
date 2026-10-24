package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.model.parseSiteIdentityMarker
import com.gatekeeper.feature.nginx.presentation.NginxConfigArtifact
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import java.io.File
import java.nio.file.Files

class ListConfigArtifactsUseCase(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val domainRules: DomainRules,
    private val nginxConflictAnalyzer: NginxConflictAnalyzer
) {
    fun execute(sites: List<SiteRecord>): List<NginxConfigArtifact> = listConfigArtifacts(
        sites,
        sitesAvailablePath = sitesAvailablePath,
        sitesEnabledPath = sitesEnabledPath
    )

    fun listConfigArtifacts(
        sites: List<SiteRecord>,
        sitesAvailablePath: String = "/etc/nginx/sites-available",
        sitesEnabledPath: String = "/etc/nginx/sites-enabled"
    ): List<NginxConfigArtifact> {
        val availableDir = File(sitesAvailablePath)
        val enabledDir = File(sitesEnabledPath)
        val filenames = (availableDir.listFiles().orEmpty().toList() + enabledDir.listFiles().orEmpty().toList())
            .asSequence()
            .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
            .map { it.name }
            .filterNot { it.startsWith(".") || it.contains(".bak-") }
            .distinct()
            .sorted()
            .toList()

        return filenames.mapNotNull { filename ->
            val available = File(availableDir, filename).takeIf { it.isFile }
            val enabled = File(enabledDir, filename)
            if (domainRules.isExcluded(available ?: File(availableDir, filename), enabled)) return@mapNotNull null
            val content = runCatching {
                (available ?: enabled.takeIf { it.exists() || Files.isSymbolicLink(it.toPath()) })?.readText().orEmpty()
            }
                .getOrDefault("")
            val classified = nginxConflictAnalyzer.classifyConfig(filename, content)
            if (classified.classification == NginxConfigClassification.SELF) return@mapNotNull null
            val identity = parseSiteIdentityMarker(content)
            val projectId = identity.projectId
            val siteId = identity.siteId
            val serviceId = Regex("(?m)^# gatekeeperd:service_id:([0-9a-fA-F-]{36})/s*$")
                .find(content)?.groupValues?.get(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            val domains = classified.serverNames
            val tracked = sites.any { site ->
                siteId == site.id ||
                        projectId != null && projectId == site.projectId && (
                        serviceId != null && serviceId == site.serviceId ||
                                serviceId == null && domains.any { it.equals(site.domain, ignoreCase = true) }
                        )
            }
            NginxConfigArtifact(
                filename = filename,
                domains = domains,
                listenPorts = classified.listenPorts,
                classification = classified.classification,
                available = available != null,
                enabled = enabled.exists() || Files.isSymbolicLink(enabled.toPath()),
                managed = identity.present,
                tracked = tracked,
                orphaned = identity.present && !tracked,
                projectId = projectId?.toString(),
                serviceId = serviceId?.toString(),
                siteId = siteId?.toString()
            )
        }
    }
}
