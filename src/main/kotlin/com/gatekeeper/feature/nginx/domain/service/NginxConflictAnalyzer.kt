package com.gatekeeper.feature.nginx.domain.service

import com.gatekeeper.feature.nginx.application.ClassifyConfigsUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.model.parseSiteIdentityMarker
import com.gatekeeper.feature.nginx.presentation.ClassifiedNginxConfig
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import com.gatekeeper.feature.nginx.presentation.NginxDomainConflict
import kotlin.collections.mapNotNull

class NginxConflictAnalyzer(
    private val nginxSelfDomainExclusion: NginxSelfDomainExclusion,
    private val nginxSiteStorage: NginxSiteStorage,
    private val domainRules: DomainRules,
    private val classifyAvailableConfigs: ClassifyConfigsUseCase
) {
    fun classifyConfig(filename: String, content: String): ClassifiedNginxConfig = classifyConfig(
        filename = filename,
        content = content,
        nginxSelfDomainExclusion = nginxSelfDomainExclusion
    )

    fun classifyConfig(filename: String, content: String, nginxSelfDomainExclusion: NginxSelfDomainExclusion): ClassifiedNginxConfig {
        val serverNames = Regex("(?m)^\\s*server_name\\s+([^;]+);")
            .findAll(content)
            .flatMap { it.groupValues[1].split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "_" }
            .distinct()
            .toList()
        val listenDirectives = Regex("(?m)^\\s*listen\\s+([^;]+);").findAll(content).toList()
        val parsedListenPorts = listenDirectives
            .mapNotNull { match ->
                val address = match.groupValues[1].trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                Regex("(?:^|:)(\\d+)$").find(address)?.groupValues?.get(1)?.toIntOrNull()
            }
            .filter { it in 1..65535 }
            .distinct()
            .sorted()
            .toList()
        val listenPorts = if (listenDirectives.isEmpty()) listOf(80) else parsedListenPorts
        val identity = parseSiteIdentityMarker(content)
        val serviceId = Regex("(?m)^# gatekeeperd:service_id:([0-9a-fA-F-]{36})\\s*$")
            .find(content)?.groupValues?.get(1)?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
        val classification = when {
            nginxSelfDomainExclusion.excludesConfig(content) -> NginxConfigClassification.SELF
            identity.present -> NginxConfigClassification.GATEKEEPER_MANAGED
            else -> NginxConfigClassification.MANUAL
        }
        return ClassifiedNginxConfig(
            filename = filename,
            serverNames = serverNames,
            listenPorts = listenPorts,
            classification = classification,
            projectId = identity.projectId?.toString(),
            serviceId = serviceId?.toString(),
            siteId = identity.siteId?.toString()
        )
    }

    fun findDomainConflicts(configContent: String, targetFilename: String): List<NginxDomainConflict> = findDomainConflicts(
        configContent = configContent,
        targetFilename = targetFilename,
        nginxSiteStorage = nginxSiteStorage,
        nginxSelfDomainExclusion = nginxSelfDomainExclusion,
        domainRules = domainRules
    )

    fun findDomainConflicts(
        configContent: String,
        targetFilename: String,
        nginxSiteStorage: NginxSiteStorage,
        nginxSelfDomainExclusion: NginxSelfDomainExclusion,
        domainRules: DomainRules
    ): List<NginxDomainConflict> {
        val candidate = classifyConfig(targetFilename, configContent, nginxSelfDomainExclusion)
        val candidatePorts = candidate.listenPorts
        if (candidate.classification == NginxConfigClassification.SELF) {
            return listOf(
                NginxDomainConflict(
                    filename = null,
                    classification = NginxConfigClassification.SELF,
                    domains = candidate.serverNames,
                    listenPorts = candidate.listenPorts,
                    matchingDomains = candidate.serverNames.filter(nginxSelfDomainExclusion::excludesDomain),
                    matchingPorts = candidatePorts,
                    linkedToRequestingSite = false
                )
            )
        }

        return classifyAvailableConfigs.classifyAvailableConfigs(nginxSiteStorage = nginxSiteStorage, selfDomainExclusion = nginxSelfDomainExclusion).mapNotNull { existing ->
            val linked = candidate.siteId != null && candidate.siteId == existing.siteId
            if (existing.filename == targetFilename && linked &&
                existing.classification == NginxConfigClassification.GATEKEEPER_MANAGED
            ) return@mapNotNull null

            val matchingPorts = candidatePorts.intersect(existing.listenPorts.toSet()).sorted()
            if (matchingPorts.isEmpty()) return@mapNotNull null
            val matchingDomains = candidate.serverNames.filter { candidateName ->
                existing.serverNames.any { existingName -> domainRules.serverNamePatternsOverlap(candidateName, existingName) }
            }.distinct()
            if (matchingDomains.isEmpty()) return@mapNotNull null

            NginxDomainConflict(
                filename = existing.filename,
                classification = existing.classification,
                domains = existing.serverNames,
                listenPorts = existing.listenPorts,
                matchingDomains = matchingDomains,
                matchingPorts = matchingPorts,
                linkedToRequestingSite = linked
            )
        }.sortedBy { it.filename }
    }
}