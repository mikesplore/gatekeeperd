package com.gatekeeper.nginx

import java.io.File

data class BackfillProject(val id: java.util.UUID, val slug: String, val containerName: String)

data class NginxBackfillReport(
    val migrated: List<String>,
    val skippedNoProject: List<String>,
    val failedDiff: Map<String, String>
) {
    val migratedCount get() = migrated.size
    val skippedCount get() = skippedNoProject.size
    val failedCount get() = failedDiff.size
}

/** Explicit/manual backfill runner. It never runs during application startup. */
class NginxSiteBackfill(
    private val sitesAvailable: File,
    private val findProject: (String) -> BackfillProject?,
    private val expectedDockerPort: (BackfillProject) -> Int?,
    private val autoCertificatePath: (String) -> String?,
    private val render: (NginxSiteRenderModel) -> String,
    private val createSite: (BackfillProject, NginxSiteRenderModel) -> Unit,
    private val deleteSite: (BackfillProject) -> Unit,
    private val dryRun: Boolean = false,
    private val log: (String) -> Unit = {}
) {
    fun run(): NginxBackfillReport {
        val migrated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()

        sitesAvailable.listFiles { file -> file.isFile && !file.name.startsWith(".") && !file.name.contains(".bak-") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val project = findProject(file.name)
                if (project == null) {
                    skipped += file.name
                    log("SKIPPED ${file.name}: no matching project")
                    return@forEach
                }
                try {
                    val original = file.readText()
                    val actual = extract(original)
                    val expectedPort = expectedDockerPort(project)
                    val dockerDiscovery = expectedPort != null && expectedPort == actual.port
                    val certAuto = autoCertificatePath(actual.domain)
                    val autoCert = actual.certPath == certAuto ||
                        (actual.tlsMode == TlsRenderMode.HTTP_ONLY && actual.certPath == null)
                    val model = NginxSiteRenderModel(
                        slug = project.slug,
                        domain = actual.domain,
                        appPort = actual.port,
                        upstreamScheme = if (actual.tlsMode == TlsRenderMode.HTTP_ONLY) "http" else "https",
                        tlsMode = actual.tlsMode,
                        certificatePath = if (autoCert) null else actual.certPath,
                        certificateKeyPath = if (autoCert) null else actual.keyPath,
                        upstreamMode = if (dockerDiscovery) com.gatekeeper.db.tables.UpstreamMode.DOCKER_DISCOVERY else com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT,
                        upstreamContainerName = if (dockerDiscovery) project.containerName else null,
                        certMode = if (autoCert) com.gatekeeper.db.tables.CertMode.AUTO_RESOLVE else com.gatekeeper.db.tables.CertMode.EXPLICIT_PATH
                    )
                    if (!dryRun) createSite(project, model)
                    val rendered = render(model)
                    if (rendered == original) {
                        migrated += project.slug
                        log("MIGRATED ${project.slug}")
                    } else {
                        if (!dryRun) deleteSite(project)
                        val diff = simpleDiff(original, rendered)
                        failed[project.slug] = diff
                        log("FAILED ${project.slug}: byte diff\n$diff")
                    }
                } catch (e: Exception) {
                    if (!dryRun) deleteSite(project)
                    failed[project.slug] = e.message ?: e::class.simpleName.orEmpty()
                    log("FAILED ${project.slug}: ${failed[project.slug]}")
                }
            }
        log("SUMMARY migrated=${migrated.size} skipped=${skipped.size} failed=${failed.size}")
        return NginxBackfillReport(migrated, skipped, failed)
    }

    private data class Extracted(val port: Int, val domain: String, val tlsMode: TlsRenderMode, val certPath: String?, val keyPath: String?)

    private fun extract(content: String): Extracted {
        val proxy = Regex("proxy_pass\\s+(?:http|https)://([^:/;\\s]+):(\\d+)\\s*;").find(content)
            ?: error("proxy_pass upstream not found")
        val domain = Regex("(?m)^\\s*server_name\\s+([^;\\s]+);\\s*$").find(content)?.groupValues?.get(1)
            ?: error("server_name not found")
        val has443 = Regex("(?m)^\\s*listen\\s+443(?:\\s|;)").containsMatchIn(content)
        val hasHttp2 = Regex("(?m)^\\s*http2\\s+on;").containsMatchIn(content)
        val tls = when {
            !has443 -> TlsRenderMode.HTTP_ONLY
            hasHttp2 -> TlsRenderMode.HTTPS_HTTP2
            else -> TlsRenderMode.HTTPS
        }
        val cert = Regex("(?m)^\\s*ssl_certificate\\s+([^;\\s]+);").find(content)?.groupValues?.get(1)
        val key = Regex("(?m)^\\s*ssl_certificate_key\\s+([^;\\s]+);").find(content)?.groupValues?.get(1)
        return Extracted(proxy.groupValues[2].toInt(), domain, tls, cert, key)
    }

    private fun simpleDiff(before: String, after: String): String {
        val beforeLines = before.lines()
        val afterLines = after.lines()
        val first = beforeLines.indices.firstOrNull { it >= afterLines.size || beforeLines[it] != afterLines[it] }
            ?: afterLines.size.coerceAtMost(beforeLines.size)
        return "first differing line ${first + 1}: before=${beforeLines.getOrNull(first)} after=${afterLines.getOrNull(first)}"
    }
}
