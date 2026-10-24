package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.presentation.ClassifiedNginxConfig
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import java.io.File
import java.nio.file.Files

/** Reads all non-backup sites-available files plus enabled-only files and classifies them without mutation. */
class ClassifyConfigsUseCase(
    private val nginxSiteStorage: NginxSiteStorage,
    private val selfDomainExclusion: NginxSelfDomainExclusion,
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
) {
    fun execute(): List<ClassifiedNginxConfig> = classifyAvailableConfigs(
        sitesEnabledPath = sitesEnabledPath,
        sitesAvailablePath = sitesAvailablePath,
        nginxSiteStorage = nginxSiteStorage,
        selfDomainExclusion = selfDomainExclusion
    )

    fun classifyAvailableConfigs(
        sitesEnabledPath: String = "/etc/nginx/sites-enabled",
        sitesAvailablePath: String = "/etc/nginx/sites-available",
        nginxSiteStorage: NginxSiteStorage,
        selfDomainExclusion: NginxSelfDomainExclusion
    ): List<ClassifiedNginxConfig> =
    (nginxSiteStorage.listConfigFiles(File(sitesAvailablePath)) + nginxSiteStorage.listConfigFiles(File(sitesEnabledPath)))
        .asSequence()
        .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
        .distinctBy { it.name }
        .filterNot { it.name.startsWith(".") || it.name.contains(".bak-") }
        .sortedBy { it.name }
        .mapNotNull { listedFile ->
            val availableFile = File(sitesAvailablePath, listedFile.name).takeIf { it.isFile }
            val enabledFile =
                File(sitesEnabledPath, listedFile.name).takeIf { it.isFile || Files.isSymbolicLink(it.toPath()) }
            val source = availableFile ?: enabledFile ?: return@mapNotNull null
            val content = try {
                source.readText()
            } catch (error: Exception) {
                throw IllegalStateException("Unable to inspect nginx config '${source.name}'", error)
            }
            val enabledContent = if (enabledFile != null && enabledFile.absolutePath != source.absolutePath) {
                try {
                    enabledFile.readText()
                } catch (error: Exception) {
                    throw IllegalStateException(
                        "Unable to inspect enabled nginx config '${enabledFile.name}'",
                        error
                    )
                }
            } else null
            val classification = classifyConfig(listedFile.name, content, nginxSelfDomainExclusion = selfDomainExclusion)
            if (enabledContent == null) classification else {
                val enabledClassification = classifyConfig(listedFile.name, enabledContent, nginxSelfDomainExclusion = selfDomainExclusion)
                classification.copy(
                    serverNames = (classification.serverNames + enabledClassification.serverNames).distinct(),
                    listenPorts = (classification.listenPorts + enabledClassification.listenPorts).distinct()
                        .sorted(),
                    projectId = classification.projectId ?: enabledClassification.projectId,
                    serviceId = classification.serviceId ?: enabledClassification.serviceId,
                    siteId = classification.siteId ?: enabledClassification.siteId,
                    classification = when {
                        classification.classification == NginxConfigClassification.SELF || enabledClassification.classification == NginxConfigClassification.SELF -> NginxConfigClassification.SELF
                        classification.classification == NginxConfigClassification.GATEKEEPER_MANAGED || enabledClassification.classification == NginxConfigClassification.GATEKEEPER_MANAGED -> NginxConfigClassification.GATEKEEPER_MANAGED
                        else -> NginxConfigClassification.MANUAL
                    }
                )
            }
        }
        .toList()
}
