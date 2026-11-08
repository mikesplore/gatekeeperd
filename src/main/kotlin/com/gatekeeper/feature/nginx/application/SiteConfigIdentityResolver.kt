package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.model.parseSiteIdentityMarker
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Resolves the physical nginx filename from Gatekeeperd ownership markers. */
class SiteConfigIdentityResolver(
    private val sitesAvailablePath: String,
    private val sitesEnabledPath: String
) {
    fun resolve(site: SiteRecord): String {
        val availableFiles = File(sitesAvailablePath).listFiles().orEmpty().toList()
        val enabledFiles = File(sitesEnabledPath).listFiles().orEmpty().toList()
        val files = (availableFiles + enabledFiles)
            .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
            .filterNot { it.name.startsWith(".") || it.name.contains(".bak-") }
            .distinctBy { it.name }
        val candidates = files.mapNotNull { file ->
            val content = runCatching {
                File(sitesAvailablePath, file.name).takeIf { it.isFile }?.readText()
                    ?: File(sitesEnabledPath, file.name).takeIf { it.exists() || Files.isSymbolicLink(it.toPath()) }?.readText()
            }.getOrNull() ?: return@mapNotNull null
            val identity = parseSiteIdentityMarker(content)
            if (!identity.present) return@mapNotNull null
            val serviceId = Regex("(?m)^# gatekeeperd:service_id:([0-9a-fA-F-]{36})\\s*$")
                .find(content)?.groupValues?.get(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val domainMatch = Regex("(?im)^\\s*server_name\\s+([^;]+);")
                .findAll(content).flatMap { it.groupValues[1].split(Regex("\\s+")) }
                .any { it.equals(site.domain, ignoreCase = true) }
            val rank = when {
                identity.siteId == site.id -> 3
                identity.projectId == site.projectId && site.serviceId != null && serviceId == site.serviceId -> 2
                identity.projectId == site.projectId && domainMatch -> 1
                else -> 0
            }
            if (rank == 0) null else file.name to rank
        }
        return candidates.maxByOrNull { it.second }?.first ?: site.projectSlug ?: site.projectId.toString()
    }
}
