package com.gatekeeper.nginx

import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.docker.DockerService
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class NginxReconciliationResult(
    val slug: String,
    val status: ReconciliationStatus,
    val nginxError: String? = null,
    val dockerError: String? = null,
    val projectId: UUID? = null
)

data class NginxReconciliationReport(
    val results: List<NginxReconciliationResult>,
    val orphanedFiles: List<String>
)

class NginxReconciliationService(
    private val sitesAvailablePath: File,
    private val sitesEnabledPath: File,
    private val listSites: () -> List<SiteRepository.SiteRecord>,
    private val renderExpected: (SiteRepository.SiteRecord) -> String,
    private val dockerCheck: (SiteRepository.SiteRecord) -> String?,
    private val nginxTest: () -> String?,
    private val persist: (SiteRepository.SiteRecord, NginxReconciliationResult) -> Unit = { site, result ->
        SiteRepository.updateReconciliation(site.id, result.status, result.nginxError, result.dockerError)
    }
) {
    companion object {
        /** Docker-backed check used by production reconciliation wiring and integration tests. */
        fun dockerCheck(docker: DockerService): (SiteRepository.SiteRecord) -> String? = { site ->
            if (site.upstreamMode != UpstreamMode.DOCKER_DISCOVERY) {
                null
            } else {
                val container = site.upstreamContainerName
                when {
                    container.isNullOrBlank() -> "Docker discovery site has no container name"
                    docker.containerHealth(container) != "running" ->
                        "Docker container '$container' is not running"
                    else -> null
                }
            }
        }
    }

    private val cacheTtlMillis = 45_000L
    @Volatile private var cached: Pair<Long, NginxReconciliationReport>? = null

    fun getSiteStatuses(): NginxReconciliationReport {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - it.first < cacheTtlMillis }?.let { return it.second }
        return synchronized(this) {
            val refreshedNow = System.currentTimeMillis()
            cached?.takeIf { refreshedNow - it.first < cacheTtlMillis }?.second ?: evaluateAll().also {
                cached = refreshedNow to it
            }
        }
    }

    fun invalidateCache() {
        cached = null
    }

    fun reconcile(): NginxReconciliationReport = getSiteStatuses()

    private fun evaluateAll(): NginxReconciliationReport {
        val sites = listSites()
        val sitesByProjectId = sites.associateBy { it.projectId }
        val sitesBySlug = sites.mapNotNull { site -> site.projectSlug?.let { it to site } }.toMap()
        val fileNames = (siteFiles(sitesAvailablePath) + siteFiles(sitesEnabledPath)).toSet()
        val resolvedFiles = fileNames.associateWith { fileName ->
            val marker = projectIdMarker(fileName)
            when {
                marker.present -> marker.projectId?.let(sitesByProjectId::get)
                else -> sitesBySlug[fileName]
            }
        }
        val filesByProjectId = resolvedFiles.entries.mapNotNull { (fileName, site) ->
            site?.let { it.projectId to fileName }
        }.groupBy({ it.first }, { it.second })
        val orphaned = resolvedFiles.filterValues { it == null }.keys.sorted()
        val nginxOutput = nginxTest()
        val results = sites.map { site ->
            val ownedFiles = filesByProjectId[site.projectId].orEmpty().sorted()
            val result = when {
                ownedFiles.size > 1 -> NginxReconciliationResult(
                    site.projectSlug ?: site.id.toString(), ReconciliationStatus.ERROR,
                    "Multiple nginx files identify project ${site.projectId}: ${ownedFiles.joinToString()}",
                    projectId = site.projectId
                )
                ownedFiles.isEmpty() && (site.projectSlug ?: site.id.toString()) in orphaned -> NginxReconciliationResult(
                    site.projectSlug ?: site.id.toString(), ReconciliationStatus.DEAD_CONFIG,
                    "Nginx file does not identify project ${site.projectId}", projectId = site.projectId
                )
                else -> evaluate(site, ownedFiles.singleOrNull() ?: site.projectSlug ?: site.id.toString(), nginxOutput)
            }
            result.also { persist(site, it) }
        }
        return NginxReconciliationReport(results, orphaned)
    }

    private fun evaluate(site: SiteRepository.SiteRecord, slug: String, nginxOutput: String?): NginxReconciliationResult {
        val available = File(sitesAvailablePath, slug)
        val enabled = File(sitesEnabledPath, slug)
        if (!available.isFile) return NginxReconciliationResult(slug, ReconciliationStatus.DEAD_CONFIG, "sites-available file is missing", projectId = site.projectId)
        if (!Files.isSymbolicLink(enabled.toPath())) return NginxReconciliationResult(slug, ReconciliationStatus.DISABLED, projectId = site.projectId)

        if (!nginxOutput.isNullOrBlank() && referencesSite(nginxOutput, available)) {
            return NginxReconciliationResult(slug, ReconciliationStatus.ERROR, nginxOutput, projectId = site.projectId)
        }

        val expected = runCatching { renderExpected(site) }.getOrElse {
            return NginxReconciliationResult(slug, ReconciliationStatus.ERROR, it.message ?: "Unable to render expected configuration", projectId = site.projectId)
        }
        if (sha256(available.readText()) != sha256(expected)) {
            return NginxReconciliationResult(slug, ReconciliationStatus.DRIFTED, projectId = site.projectId)
        }

        val dockerError = dockerCheck(site)
        if (dockerError != null) return NginxReconciliationResult(slug, ReconciliationStatus.DOCKER_DOWN, dockerError = dockerError, projectId = site.projectId)
        return NginxReconciliationResult(slug, ReconciliationStatus.HEALTHY, projectId = site.projectId)
    }

    private fun projectIdMarker(fileName: String): SiteProjectIdentityMarker {
        val file = File(sitesAvailablePath, fileName).takeIf { it.isFile }
            ?: File(sitesEnabledPath, fileName).takeIf { it.exists() }
            ?: return SiteProjectIdentityMarker(false, null)
        val content = runCatching { file.readText() }.getOrDefault("")
        return parseSiteProjectIdentityMarker(content)
    }

    private fun referencesSite(output: String, file: File): Boolean =
        output.contains(file.absolutePath) || output.contains(file.name)

    private fun siteFiles(directory: File): List<String> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
        .map { it.name }
        .filterNot { it.startsWith(".") || it.contains(".bak-") }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
