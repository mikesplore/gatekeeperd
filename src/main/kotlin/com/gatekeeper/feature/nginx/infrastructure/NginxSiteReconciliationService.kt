package com.gatekeeper.feature.nginx.infrastructure

import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.SiteIdentityMarker
import com.gatekeeper.feature.nginx.model.parseSiteIdentityMarker
import com.gatekeeper.feature.nginx.presentation.NginxReconciliationReport
import com.gatekeeper.feature.nginx.presentation.NginxReconciliationResult
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest


class NginxSiteReconciliationService(
    private val settings: AppSettings.NginxSettings,
    private val siteRepository: SiteRepository,
    private val deploymentUpstreamResolver: DeploymentUpstreamResolver,
    private val dockerGateway: DockerGateway,
    private val nginxConfigRenderer: NginxConfigRenderer,
    private val selfDomainExclusion: NginxSelfDomainExclusion = NginxSelfDomainExclusion(emptyList())
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val sitesAvailablePath: File get() = File(settings.sitesAvailablePath)
    private val sitesEnabledPath: File get() = File(settings.sitesEnabledPath)

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
        val sites = siteRepository.findAll().filterNot { selfDomainExclusion.excludesDomain(it.domain) }
        val sitesBySiteId = sites.associateBy { it.id }
        val fileNames = (siteFiles(sitesAvailablePath) + siteFiles(sitesEnabledPath))
            .toSet()
            .filterNot(::isExcludedConfig)

        val resolvedFiles = fileNames.associateWith { fileName ->
            val marker = siteIdentityMarker(fileName)
            when {
                marker.present && marker.siteId != null -> sitesBySiteId[marker.siteId]
                marker.present -> marker.projectId?.let { projectId ->
                    sites.firstOrNull { it.projectId == projectId && it.domain.equals(File(sitesAvailablePath, fileName).name, ignoreCase = true) }
                        ?: sites.firstOrNull { it.projectId == projectId && fileName.endsWith(it.domain.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-'), ignoreCase = true) }
                        ?: sites.firstOrNull { it.projectId == projectId }
                }
                else -> null
            }
        }

        val filesBySiteId = resolvedFiles.entries.mapNotNull { (fileName, site) ->
            site?.let { it.id to fileName }
        }.groupBy({ it.first }, { it.second })

        val orphaned = resolvedFiles.filterValues { it == null }.keys.sorted()
        val nginxOutput = testNginxConfiguration()

        val results = sites.map { site ->
            val ownedFiles = filesBySiteId[site.id].orEmpty().sorted()
            val result = when {
                ownedFiles.size > 1 -> NginxReconciliationResult(
                    site.id.toString(), ReconciliationStatus.ERROR,
                    "Multiple nginx files identify site ${site.id}: ${ownedFiles.joinToString()}",
                    projectId = site.projectId
                )
                ownedFiles.isEmpty() && site.id.toString() in orphaned -> NginxReconciliationResult(
                    site.id.toString(), ReconciliationStatus.DEAD_CONFIG,
                    "Nginx file does not identify site ${site.id}", projectId = site.projectId
                )
                else -> evaluate(site, ownedFiles.singleOrNull() ?: site.id.toString(), nginxOutput)
            }
            siteRepository.updateReconciliation(site.id, result.status, result.nginxError, result.dockerError)
            result
        }
        return NginxReconciliationReport(results, orphaned)
    }

    private fun evaluate(site: SiteRecord, slug: String, nginxOutput: String?): NginxReconciliationResult {
        val available = File(sitesAvailablePath, slug)
        val enabled = File(sitesEnabledPath, slug)

        if (!available.isFile) return NginxReconciliationResult(slug, ReconciliationStatus.DEAD_CONFIG, "sites-available file is missing", projectId = site.projectId)
        if (!Files.isSymbolicLink(enabled.toPath())) return NginxReconciliationResult(slug, ReconciliationStatus.DISABLED, projectId = site.projectId)

        if (!nginxOutput.isNullOrBlank() && referencesSite(nginxOutput, available)) {
            return NginxReconciliationResult(slug, ReconciliationStatus.ERROR, nginxOutput, projectId = site.projectId)
        }

        val expected = runCatching { renderExpectedConfig(site) }.getOrElse {
            return NginxReconciliationResult(slug, ReconciliationStatus.ERROR, it.message ?: "Unable to render expected configuration", projectId = site.projectId)
        }

        if (sha256(available.readText()) != sha256(expected)) {
            return NginxReconciliationResult(slug, ReconciliationStatus.DRIFTED, projectId = site.projectId)
        }

        val dockerError = checkDockerHealth(site)
        if (dockerError != null) return NginxReconciliationResult(slug, ReconciliationStatus.DOCKER_DOWN, dockerError = dockerError, projectId = site.projectId)

        return NginxReconciliationResult(slug, ReconciliationStatus.HEALTHY, projectId = site.projectId)
    }

    private fun checkDockerHealth(site: SiteRecord): String? {
        if (site.upstreamMode != UpstreamMode.DOCKER_DISCOVERY) return null
        val serviceId = site.serviceId ?: return "Site has no service identity"

        val container = deploymentUpstreamResolver.resolve(serviceId, "production")?.containerName
        return when {
            container.isNullOrBlank() -> "Docker discovery site has no container name"
            dockerGateway.containerHealth(container) != "running" -> "Docker container '$container' is not running"
            else -> null
        }
    }

    private fun renderExpectedConfig(site: NginxSiteRenderModel): String {
        // Delegate to your Nginx template renderer here
        /*TODO*/
        return nginxConfigRenderer.render(site)
    }

    private fun testNginxConfiguration(): String? = runCatching {
        val process = ProcessBuilder("nginx", "-t").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode == 0) null else output.trim()
    }.getOrElse {
        logger.error("Failed to execute nginx -t check", it)
        null
    }

    private fun siteIdentityMarker(fileName: String): SiteIdentityMarker {
        val file = File(sitesAvailablePath, fileName).takeIf { it.isFile }
            ?: File(sitesEnabledPath, fileName).takeIf { it.exists() }
            ?: return SiteIdentityMarker(false, null, null)
        val content = runCatching { file.readText() }.getOrDefault("")
        return parseSiteIdentityMarker(content)
    }

    private fun isExcludedConfig(fileName: String): Boolean =
        selfDomainExclusion.excludesFile(File(sitesAvailablePath, fileName)) ||
                selfDomainExclusion.excludesFile(File(sitesEnabledPath, fileName))

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