package com.gatekeeper.nginx

import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.ReconciliationStatus
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime

data class NginxReconciliationResult(
    val slug: String,
    val status: ReconciliationStatus,
    val nginxError: String? = null,
    val dockerError: String? = null
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
        val bySlug = sites.mapNotNull { it.projectSlug?.let { slug -> slug to it } }.toMap()
        val fileNames = (siteFiles(sitesAvailablePath) + siteFiles(sitesEnabledPath)).toSet()
        val orphaned = fileNames.filter { it !in bySlug }.sorted()
        val nginxOutput = nginxTest()
        val results = sites.map { site ->
            val slug = site.projectSlug ?: site.id.toString()
            evaluate(site, slug, nginxOutput).also { persist(site, it) }
        }
        return NginxReconciliationReport(results, orphaned)
    }

    private fun evaluate(site: SiteRepository.SiteRecord, slug: String, nginxOutput: String?): NginxReconciliationResult {
        val available = File(sitesAvailablePath, slug)
        val enabled = File(sitesEnabledPath, slug)
        if (!available.isFile) return NginxReconciliationResult(slug, ReconciliationStatus.DEAD_CONFIG, "sites-available file is missing")
        if (!Files.isSymbolicLink(enabled.toPath())) return NginxReconciliationResult(slug, ReconciliationStatus.DISABLED)

        if (!nginxOutput.isNullOrBlank() && referencesSite(nginxOutput, available)) {
            return NginxReconciliationResult(slug, ReconciliationStatus.ERROR, nginxOutput)
        }

        val expected = runCatching { renderExpected(site) }.getOrElse {
            return NginxReconciliationResult(slug, ReconciliationStatus.ERROR, it.message ?: "Unable to render expected configuration")
        }
        if (sha256(available.readText()) != sha256(expected)) {
            return NginxReconciliationResult(slug, ReconciliationStatus.DRIFTED)
        }

        val dockerError = dockerCheck(site)
        if (dockerError != null) return NginxReconciliationResult(slug, ReconciliationStatus.DOCKER_DOWN, dockerError = dockerError)
        return NginxReconciliationResult(slug, ReconciliationStatus.HEALTHY)
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
