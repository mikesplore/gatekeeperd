package com.gatekeeper.nginx

import com.gatekeeper.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.security.cert.CertificateFactory
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.security.MessageDigest
import com.gatekeeper.plugins.DistributedLock
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode

private val logger = LoggerFactory.getLogger("com.gatekeeper.nginx.NginxService")

data class NginxSiteActivationResult(val success: Boolean, val message: String? = null)

data class ResolvedCertificate(
    val certificateDomain: String,
    val certificatePath: String,
    val privateKeyPath: String
)

class NginxService(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = "/etc/letsencrypt/live",
    private val nginxTestRunner: () -> NginxTestResult = { NginxCommandRunner().test() },
    private val nginxReloadRunner: () -> Boolean = { NginxCommandRunner().reload() },
    private val selfDomainExclusion: NginxSelfDomainExclusion = NginxSelfDomainExclusion(emptyList()),
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams",
    private val localProbeRunner: (String, Boolean) -> Boolean = ::probeViaLocalNginx,
    private val nginxConfigDumpRunner: () -> String? = { NginxCommandRunner().dumpConfig() }
) {
    enum class InterruptedCutoverResult { TARGET_RELOADED, PREVIOUS_RESTORED, CONFLICT }
    companion object {
        fun configured(commands: NginxCommandRunner = NginxCommandRunner()): NginxService = NginxService(
            sitesAvailablePath = AppConfig.nginxSitesAvailablePath,
            sitesEnabledPath = AppConfig.nginxSitesEnabledPath,
            sslCertPath = AppConfig.nginxSslCertPath,
            upstreamIncludeDirectory = AppConfig.nginxUpstreamIncludeDirectory,
            selfDomainExclusion = NginxSelfDomainExclusion(AppConfig.gatekeeperdSelfDomains),
            nginxTestRunner = commands::test,
            nginxReloadRunner = commands::reload,
            nginxConfigDumpRunner = commands::dumpConfig
        )
    }

    fun bootstrapDeploymentUpstreams() {
        val directory = File(upstreamIncludeDirectory).absoluteFile
        check((directory.isDirectory || directory.mkdirs()) && directory.canWrite()) {
            "Cannot create or write nginx deployment upstream include directory: ${directory.absolutePath}"
        }
        val dump = nginxConfigDumpRunner()
            ?: error("Unable to read active nginx configuration with nginx -T; deployment upstream bootstrap cannot continue")
        check(nginxIncludesDirectoryInHttpContext(dump, directory.path)) {
            "nginx.conf must include ${directory.path}/*.conf inside the http {} block before Gatekeeperd deployment cutovers can start"
        }
    }

    fun inspectDeploymentUpstreamPort(serviceId: java.util.UUID): Int? = runCatching {
        val content = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory)).file(serviceId).takeIf { it.isFile }?.readText()
            ?: return null
        Regex("""(?m)^\s*server\s+127\.0\.0\.1:(\d+)\s*;""")
            .find(content)?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    fun inspectSite(slug: String): NginxConfigInspection {
        require(slug.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid nginx site name" }
        val availableFile = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
        require(!isExcluded(availableFile, enabledFile)) { "This nginx config serves Gatekeeperd and is excluded from site management" }
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
            blocks = content?.let(::extractBlocks).orEmpty(),
            customLocations = runCatching { SiteRepository.findByProjectSlug(slug)?.customNginxLocations.orEmpty() }.getOrDefault(emptyList()),
            modifiedAt = availableFile.takeIf { it.exists() }?.let { Instant.ofEpochMilli(it.lastModified()).toString() },
            sizeBytes = availableFile.takeIf { it.exists() }?.length()
            ,managed = content?.contains("# gatekeeperd:block:") == true,
            manual = content != null && !content.contains("# gatekeeperd:block:"),
            drifted = managedHash != null && actualHash != managedHash,
            actualSha256 = actualHash,
            managedSha256 = managedHash
        )
    }

    fun listConfigArtifacts(sites: List<SiteRepository.SiteRecord> = SiteRepository.findAll()): List<NginxConfigArtifact> {
        val availableDir = File(sitesAvailablePath)
        val enabledDir = File(sitesEnabledPath)
        val filenames = (availableDir.listFiles().orEmpty().toList() + enabledDir.listFiles().orEmpty().toList())
            .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
            .map { it.name }
            .filterNot { it.startsWith(".") || it.contains(".bak-") }
            .distinct()
            .sorted()

        return filenames.mapNotNull { filename ->
            val available = File(availableDir, filename).takeIf { it.isFile }
            val enabled = File(enabledDir, filename)
            if (isExcluded(available ?: File(availableDir, filename), enabled)) return@mapNotNull null
            val content = runCatching { (available ?: enabled.takeIf { it.exists() || Files.isSymbolicLink(it.toPath()) })?.readText().orEmpty() }
                .getOrDefault("")
            val classified = classifyConfig(filename, content)
            if (classified.classification == NginxConfigClassification.SELF) return@mapNotNull null
            val identity = parseSiteIdentityMarker(content)
            val projectId = identity.projectId
            val siteId = identity.siteId
            val serviceId = Regex("(?m)^# gatekeeperd:service_id:([0-9a-fA-F-]{36})\\s*$")
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

    fun inspectManualConfig(filename: String): NginxManualConfigDetail {
        require(isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val available = File(sitesAvailablePath, filename)
        val enabled = File(sitesEnabledPath, filename)
        require(!isExcluded(available, enabled)) { "This nginx config serves Gatekeeperd and is excluded from site management" }
        val source = available.takeIf { it.isFile } ?: enabled.takeIf { it.isFile || Files.isSymbolicLink(it.toPath()) }
            ?: throw IllegalArgumentException("Nginx config not found")
        val content = source.readText()
        require(classifyConfig(filename, content).classification == NginxConfigClassification.MANUAL) {
            "Only unmarked manual nginx configs can be inspected here"
        }
        val classified = classifyConfig(filename, content)
        return NginxManualConfigDetail(filename, classified.serverNames, classified.listenPorts,
            available.isFile, enabled.exists() || Files.isSymbolicLink(enabled.toPath()), content)
    }

    fun disableManualConfig(filename: String): NginxManualConfigDisableResult =
        DistributedLock.withLock("nginx-host-mutation") { disableManualConfigLocked(filename) }

    private fun disableManualConfigLocked(filename: String): NginxManualConfigDisableResult {
        require(isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val detail = inspectManualConfig(filename)
        require(detail.enabled) { "Manual nginx config is already disabled" }
        val available = File(sitesAvailablePath, filename)
        val availableWasPresent = available.isFile
        val enabled = File(sitesEnabledPath, filename)
        val wasSymlink = Files.isSymbolicLink(enabled.toPath())
        val linkTarget = if (wasSymlink) Files.readSymbolicLink(enabled.toPath()) else null
        val backup = File(available.parentFile, "$filename.bak-${Instant.now().toEpochMilli()}")
        if (!availableWasPresent) Files.writeString(available.toPath(), detail.content)
        Files.copy(available.toPath(), backup.toPath())
        Files.deleteIfExists(enabled.toPath())

        val validation = testNginxConfigDetailed()
        if (!validation.valid) {
            restoreEnabledManualConfig(available, enabled, wasSymlink, linkTarget)
            if (!availableWasPresent) Files.deleteIfExists(available.toPath())
            throw IllegalStateException("nginx -t failed after disabling '$filename'; the enabled config was restored: ${validation.output}")
        }
        if (!nginxReloadRunner()) {
            restoreEnabledManualConfig(available, enabled, wasSymlink, linkTarget)
            if (!availableWasPresent) Files.deleteIfExists(available.toPath())
            runCatching { reloadNginx() }
            throw IllegalStateException("Nginx reload failed after disabling '$filename'; the enabled config was restored")
        }

        val afterDisable = listConfigArtifacts()
        val matchingManaged = afterDisable.filter { artifact ->
            artifact.classification == NginxConfigClassification.GATEKEEPER_MANAGED && artifact.tracked && artifact.enabled &&
                domainsOverlap(detail.domains, artifact.domains) && portsOverlap(detail.listenPorts, artifact.listenPorts)
        }
        if (matchingManaged.isNotEmpty()) {
            val competing = afterDisable.filter { artifact ->
                artifact.filename != filename && artifact.enabled &&
                    domainsOverlap(detail.domains, artifact.domains) && portsOverlap(detail.listenPorts, artifact.listenPorts) &&
                    !(artifact.classification == NginxConfigClassification.GATEKEEPER_MANAGED && artifact.tracked && matchingManaged.any { it.filename == artifact.filename })
            }
            if (competing.isNotEmpty()) {
                restoreEnabledManualConfig(available, enabled, wasSymlink, linkTarget)
                if (!availableWasPresent) Files.deleteIfExists(available.toPath())
                runCatching { reloadNginx() }
                throw IllegalStateException("The domain also matches enabled configs (${competing.joinToString { it.filename }}); the manual config was restored because traffic could not be verified to reach the intended Gatekeeper-managed site")
            }
        }
        return NginxManualConfigDisableResult(backup.name, matchingManaged.map { it.filename })
    }

    fun deleteDisabledManualConfig(filename: String): String =
        DistributedLock.withLock("nginx-host-mutation") { deleteDisabledManualConfigLocked(filename) }

    private fun deleteDisabledManualConfigLocked(filename: String): String {
        require(isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val detail = inspectManualConfig(filename)
        require(!detail.enabled) { "Disable the manual nginx config before deleting it" }
        val available = File(sitesAvailablePath, filename)
        require(available.isFile) { "Manual nginx config is not present in sites-available" }

        val backup = File(available.parentFile, "$filename.bak-${Instant.now().toEpochMilli()}")
        Files.copy(available.toPath(), backup.toPath())
        Files.delete(available.toPath())
        val validation = testNginxConfigDetailed()
        if (!validation.valid) {
            Files.copy(backup.toPath(), available.toPath())
            throw IllegalStateException("nginx -t failed after deleting '$filename'; the config was restored from backup: ${validation.output}")
        }
        return backup.name
    }

    private fun restoreEnabledManualConfig(available: File, enabled: File, wasSymlink: Boolean, linkTarget: java.nio.file.Path?) {
        if (wasSymlink && linkTarget != null) Files.createSymbolicLink(enabled.toPath(), linkTarget)
        else if (available.isFile) Files.copy(available.toPath(), enabled.toPath())
    }

    private fun isSafeConfigFilename(filename: String): Boolean =
        filename == File(filename).name && filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))

    private fun normalizeServerName(name: String): String = name.trim().trimEnd('.').lowercase()

    private fun domainsOverlap(first: List<String>, second: List<String>): Boolean = first.any { left ->
        second.any { right -> serverNamesMayMatch(normalizeServerName(left), normalizeServerName(right)) }
    }

    private fun serverNamesMayMatch(first: String, second: String): Boolean = when {
        first == second -> true
        first.startsWith("*.") -> second.endsWith(first.removePrefix("*")) || second.startsWith("*.") && first == second
        second.startsWith("*.") -> first.endsWith(second.removePrefix("*"))
        first.startsWith(".") -> second == first.removePrefix(".") || second.endsWith(first)
        second.startsWith(".") -> first == second.removePrefix(".") || first.endsWith(second)
        else -> false
    }

    private fun portsOverlap(first: List<Int>, second: List<Int>): Boolean =
        first.isEmpty() || second.isEmpty() || first.any { it in second }

    /** Reads all non-backup sites-available files plus enabled-only files and classifies them without mutation. */
    fun classifyAvailableConfigs(): List<ClassifiedNginxConfig> =
        (listConfigFiles(File(sitesAvailablePath)) + listConfigFiles(File(sitesEnabledPath)))
            .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
            .distinctBy { it.name }
            .filterNot { it.name.startsWith(".") || it.name.contains(".bak-") }
            .sortedBy { it.name }
            .mapNotNull { listedFile ->
                val availableFile = File(sitesAvailablePath, listedFile.name).takeIf { it.isFile }
                val enabledFile = File(sitesEnabledPath, listedFile.name).takeIf { it.isFile || Files.isSymbolicLink(it.toPath()) }
                val source = availableFile ?: enabledFile ?: return@mapNotNull null
                val content = try {
                    source.readText()
                } catch (error: Exception) {
                    throw IllegalStateException("Unable to inspect nginx config '${source.name}'", error)
                }
                val enabledContent = if (enabledFile != null && enabledFile.absolutePath != source.absolutePath) {
                    try { enabledFile.readText() } catch (error: Exception) {
                        throw IllegalStateException("Unable to inspect enabled nginx config '${enabledFile.name}'", error)
                    }
                } else null
                val classification = classifyConfig(listedFile.name, content)
                if (enabledContent == null) classification else {
                    val enabledClassification = classifyConfig(listedFile.name, enabledContent)
                    classification.copy(
                        serverNames = (classification.serverNames + enabledClassification.serverNames).distinct(),
                        listenPorts = (classification.listenPorts + enabledClassification.listenPorts).distinct().sorted(),
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

    private fun listConfigFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Nginx config path '${directory.absolutePath}' is not a directory" }
        return directory.listFiles()?.toList()
            ?: error("Unable to list nginx config directory '${directory.absolutePath}'")
    }

    fun findDomainConflicts(configContent: String, targetFilename: String): List<NginxDomainConflict> {
        val candidate = classifyConfig(targetFilename, configContent)
        val candidatePorts = candidate.listenPorts
        if (candidate.classification == NginxConfigClassification.SELF) {
            return listOf(NginxDomainConflict(
                filename = null,
                classification = NginxConfigClassification.SELF,
                domains = candidate.serverNames,
                listenPorts = candidate.listenPorts,
                matchingDomains = candidate.serverNames.filter(selfDomainExclusion::excludesDomain),
                matchingPorts = candidatePorts,
                linkedToRequestingSite = false
            ))
        }

        return classifyAvailableConfigs().mapNotNull { existing ->
            val linked = candidate.siteId != null && candidate.siteId == existing.siteId
            if (existing.filename == targetFilename && linked &&
                existing.classification == NginxConfigClassification.GATEKEEPER_MANAGED) return@mapNotNull null

            val matchingPorts = candidatePorts.intersect(existing.listenPorts.toSet()).sorted()
            if (matchingPorts.isEmpty()) return@mapNotNull null
            val matchingDomains = candidate.serverNames.filter { candidateName ->
                existing.serverNames.any { existingName -> serverNamePatternsOverlap(candidateName, existingName) }
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

    private fun classifyConfig(filename: String, content: String): ClassifiedNginxConfig {
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
            selfDomainExclusion.excludesConfig(content) -> NginxConfigClassification.SELF
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

    private fun serverNamePatternsOverlap(first: String, second: String): Boolean {
        val a = first.trim().trimEnd('.').lowercase()
        val b = second.trim().trimEnd('.').lowercase()
        if (a.startsWith("~") || b.startsWith("~")) return true
        if (serverNamePatternMatches(a, b) || serverNamePatternMatches(b, a)) return true
        val aWildcardBase = a.removePrefix("*.").removePrefix(".")
        val bWildcardBase = b.removePrefix("*.").removePrefix(".")
        val aWildcard = a.startsWith("*.") || a.startsWith(".")
        val bWildcard = b.startsWith("*.") || b.startsWith(".")
        return aWildcard && bWildcard && (
            aWildcardBase == bWildcardBase ||
                aWildcardBase.endsWith(".$bWildcardBase") ||
                bWildcardBase.endsWith(".$aWildcardBase")
            )
    }

    private fun serverNamePatternMatches(pattern: String, hostname: String): Boolean = when {
        pattern.startsWith("*.") -> hostname.endsWith(pattern.removePrefix("*").let { ".$it" })
        pattern.startsWith(".") -> hostname == pattern.removePrefix(".") || hostname.endsWith(pattern)
        else -> pattern == hostname
    }

    fun removeSiteArtifacts(site: SiteRepository.SiteRecord): Boolean {
        val matches = listConfigArtifacts(listOf(site)).filter { artifact ->
            artifact.siteId == site.id.toString() ||
                artifact.projectId == site.projectId.toString() && (
                    artifact.serviceId == site.serviceId?.toString() ||
                        artifact.serviceId == null && artifact.domains.any { it.equals(site.domain, ignoreCase = true) }
                    )
        }.map { it.filename }.toMutableSet()
        site.projectSlug?.let(matches::add)
        return matches.all(::removeProject)
    }

    fun restoreSiteConfiguration(slug: String, previousConfig: String): Boolean = enableProject(slug, previousConfig)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun recordManagedVersion(slug: String, content: String) {
        Files.writeString(File(sitesAvailablePath, ".$slug.gatekeeperd.sha256").toPath(), sha256(content))
    }

    private fun extractBlocks(content: String): List<NginxConfigBlock> = NginxConfigParser.parseBlocks(content)

    fun testNginxConfigDetailed(): NginxTestResult {
        return nginxTestRunner()
    }

    @Suppress("UNUSED_PARAMETER")
    fun previewBlockUpdate(slug: String, blockIndex: Int, replacement: String): String {
        val current = inspectSite(slug)
        require(current.available) { "Nginx site '$slug' does not exist" }
        require(!current.manual) { "Nginx site '$slug' is manual/unmanaged; edit the file directly. Gatekeeperd will only detect drift." }
        require(blockIndex in current.blocks.indices) { "Block index $blockIndex is out of range" }
        throw IllegalArgumentException("Managed Nginx blocks are read-only. Add or remove custom location blocks through the custom locations API.")
    }

    fun validateCustomLocations(locations: List<NginxCustomLocation>) {
        require(locations.size <= 20) { "A site can have at most 20 custom location blocks" }
        locations.forEach(::validateCustomLocation)
        require(locations.map { it.path.lowercase() }.distinct().size == locations.size) { "Custom location paths must be unique" }
    }

    private fun validateCustomLocation(location: NginxCustomLocation) {
        require(location.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid custom location ID" }
        require(location.path.matches(Regex("/(?:[A-Za-z0-9._~-]+/?)+")) &&
            location.path.split('/').none { it == "." || it == ".." }) {
            "Location path must be a literal URI prefix such as /socket.io/"
        }
        require(location.path != "/") { "The root location is reserved for the application route" }
        val reserved = listOf("/api/gate", "/api/paystack", "/api/mpesa", "/gatekeeper-auth-", "/.well-known/gatekeeperd-cutover-probe")
        require(reserved.none { location.path.startsWith(it, ignoreCase = true) }) { "This path is reserved for Gatekeeper routes" }
        require(location.directives.isNotBlank() && location.directives.length <= 16_000) { "Add directives (maximum 16,000 characters)" }
        val forbidden = Regex("(?im)^\\s*(?:location|server|listen|include|auth_request|error_page|satisfy|allow|deny|return|rewrite|try_files|internal)\\b")
        require(!forbidden.containsMatchIn(location.directives)) { "Custom locations cannot define nested routes or override Gatekeeper access control" }
        require(!location.directives.contains('{') && !location.directives.contains('}')) { "Nested nginx blocks are not supported in custom locations" }
        require(location.directives.lineSequence().all { it.isBlank() || it.trimEnd().endsWith(';') }) { "Each directive must end with a semicolon" }
        val proxyPasses = location.directives.lineSequence().filter { it.trimStart().matches(Regex("^proxy_pass(?:\\s|$).*")) }
        require(proxyPasses.none()) {
            "The application upstream is added automatically; custom proxy_pass directives are not allowed"
        }
    }

    fun applyBlockUpdate(slug: String, blockIndex: Int, replacement: String): NginxBlockUpdateResponse =
        DistributedLock.withLock("nginx-host-mutation") { applyBlockUpdateLocked(slug, blockIndex, replacement) }

    private fun applyBlockUpdateLocked(slug: String, blockIndex: Int, replacement: String): NginxBlockUpdateResponse {
        val file = File(sitesAvailablePath, slug)
        val proposed = previewBlockUpdate(slug, blockIndex, replacement)
        val current = file.readText()
        val backup = File(file.parentFile, "$slug.bak-${Instant.now().toEpochMilli()}")
        Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(file.toPath(), proposed)
        val validation = testNginxConfigDetailed()
        if (!validation.valid) {
            Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return NginxBlockUpdateResponse(false, "Nginx validation failed; previous configuration restored", current, blockIndex, validation)
        }
        if (!reloadNginx()) {
            Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            reloadNginx()
            return NginxBlockUpdateResponse(false, "Nginx reload failed; previous configuration restored", current, blockIndex, validation)
        }
        recordManagedVersion(slug, proposed)
        return NginxBlockUpdateResponse(true, "Nginx block updated and reloaded successfully", proposed, blockIndex, validation, true)
    }

    fun listBackups(slug: String): List<NginxBackup> {
        require(slug.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid nginx site name" }
        require(!isExcluded(File(sitesAvailablePath, slug), File(sitesEnabledPath, slug))) { "This nginx config serves Gatekeeperd and is excluded from site management" }
        return File(sitesAvailablePath).listFiles { file -> file.name.startsWith("$slug.bak-") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { NginxBackup(it.name, Instant.ofEpochMilli(it.lastModified()).toString(), it.length()) }
            .orEmpty()
    }

    fun rollback(slug: String, backupName: String): NginxRollbackResponse =
        DistributedLock.withLock("nginx-host-mutation") { rollbackLocked(slug, backupName) }

    private fun rollbackLocked(slug: String, backupName: String): NginxRollbackResponse {
        require(backupName.matches(Regex("${Regex.escape(slug)}\\.bak-[0-9]+"))) { "Invalid backup name" }
        val backup = File(sitesAvailablePath, backupName)
        require(backup.isFile) { "Backup does not exist" }
        val target = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
        require(!isExcluded(target, enabledFile) && !selfDomainExclusion.excludesFile(backup)) {
            "This nginx config serves Gatekeeperd and is excluded from site management"
        }
        validateProtectedGateBlocks(slug, backup.readText())
        val stagedFile = File(sitesAvailablePath, ".${slug}.staged")
        Files.copy(backup.toPath(), stagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val validation = testNginxConfigWithStagedSite(slug, stagedFile)
        if (!validation.valid) {
            Files.deleteIfExists(stagedFile.toPath())
            return NginxRollbackResponse(false, "Rollback validation failed; previous configuration was preserved", validation)
        }

        val current = if (target.isFile) {
            File(sitesAvailablePath, "$slug.bak-${Instant.now().toEpochMilli()}").also {
                Files.copy(target.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else null
        try {
            try {
                Files.move(stagedFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(stagedFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            replaceEnabledSymlink(target, enabledFile)
            if (!reloadNginx()) {
                restoreActivatedSite(target, enabledFile, current)
                reloadNginx()
                return NginxRollbackResponse(false, "Rollback reload failed; previous configuration was restored", validation)
            }
        } catch (_: Exception) {
            restoreActivatedSite(target, enabledFile, current)
            runCatching { reloadNginx() }
            return NginxRollbackResponse(false, "Rollback activation failed; previous configuration was restored", validation)
        } finally {
            Files.deleteIfExists(stagedFile.toPath())
        }
        recordManagedVersion(slug, target.readText())
        return NginxRollbackResponse(true, "Configuration rolled back and Nginx reloaded successfully", validation, true)
    }

    private fun validateProtectedGateBlocks(slug: String, config: String) {
        val gateEnabled = runCatching { SiteRepository.findByProjectSlug(slug)?.gateEnabled }.getOrNull() ?: true
        if (!gateEnabled) return
        require("# gatekeeperd:block:auth_request" in config && "# gatekeeperd:block:paywall" in config) {
            "This backup is missing Gatekeeper's protected auth or paywall block"
        }
        require(Regex("(?m)^\\s*auth_request /gatekeeper-auth-${Regex.escape(slug)};\\s*$").containsMatchIn(config) &&
            Regex("(?m)^\\s*error_page 403 = @gatekeeper_paywall_${Regex.escape(slug)};\\s*$").containsMatchIn(config)) {
            "This backup does not preserve Gatekeeper's auth and paywall directives"
        }
    }

    fun certificateExpiry(domain: String): Pair<String, Long>? {
        return try {
            requireValidHostname(domain)
            val certFile = File("$sslCertPath/$domain/fullchain.pem")
            if (!certFile.exists()) return null
            val pem = certFile.readBytes()
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem)) as java.security.cert.X509Certificate
            val expiry = certificate.notAfter.toInstant().atOffset(ZoneOffset.UTC).toString()
            val days = java.time.Duration.between(Instant.now(), certificate.notAfter.toInstant()).toDays()
            expiry to days
        } catch (_: Exception) {
            null
        }
    }

    init {
        logger.info("NginxService initialized with sites-available: $sitesAvailablePath, sites-enabled: $sitesEnabledPath")
    }
    fun isPortActive(port: Int): Boolean {
        return try {
            listOf("127.0.0.1", "::1").any { host ->
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 250)
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun generateNginxConfig(
        site: NginxSiteRenderModel
    ): String = generateNginxConfig(
        slug = site.slug,
        domain = site.domain,
        appPort = site.appPort,
        upstreamScheme = site.upstreamScheme,
        sslEnabled = site.tlsMode != TlsRenderMode.HTTP_ONLY,
        sslCertificatePath = site.certificatePath,
        sslCertificateKeyPath = site.certificateKeyPath,
        upstreamHost = site.upstreamHost,
        http2 = site.tlsMode == TlsRenderMode.HTTPS_HTTP2,
        gateEnabled = site.gateEnabled,
        bypassPaths = site.bypassPaths,
        projectId = site.projectId,
        serviceId = site.serviceId,
        siteId = site.siteId,
        upstreamKind = site.upstreamKind,
        customNginxLocations = site.customNginxLocations
    )

    fun generateNginxConfig(
        slug: String,
        domain: String,
        appPort: Int,
        upstreamScheme: String? = null,
        sslEnabled: Boolean,
        sslCertificatePath: String? = null,
        sslCertificateKeyPath: String? = null,
        upstreamHost: String = "127.0.0.1",
        http2: Boolean = true,
        gateEnabled: Boolean = true,
        bypassPaths: List<String> = DEFAULT_GATEKEEPER_BYPASS_PATHS,
        projectId: java.util.UUID? = null,
        serviceId: java.util.UUID? = null,
        siteId: java.util.UUID? = null,
        upstreamKind: String? = null,
        customNginxLocations: List<NginxCustomLocation> = emptyList()
    ): String = NginxConfigRenderer(gatekeeperPort, sslCertPath, upstreamIncludeDirectory).render(
        slug, domain, appPort, upstreamScheme, sslEnabled, sslCertificatePath, sslCertificateKeyPath,
        upstreamHost, http2, gateEnabled, bypassPaths, projectId, serviceId, siteId, upstreamKind, customNginxLocations
    )

    private fun normalizeUpstreamScheme(appPort: Int, upstreamScheme: String?): String {
        val normalized = upstreamScheme?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            return if (appPort == 443) "https" else "http"
        }

        require(normalized == "http" || normalized == "https") {
            "upstreamScheme must be either http or https"
        }
        return normalized
    }

    fun enableProject(slug: String, configContent: String): Boolean {
        return enableProjectDetailed(slug, configContent).success
    }

    fun enableProjectDetailed(slug: String, configContent: String): NginxSiteActivationResult {
        return DistributedLock.withLock("nginx-host-mutation") {
          try {
            val conflicts = findDomainConflicts(configContent, slug)
            if (conflicts.isNotEmpty()) {
                val message = "Nginx domain conflict: ${conflicts.joinToString { it.filename ?: "Gatekeeperd self domain" }}"
                logger.warn("Refusing to enable nginx site $slug because it conflicts with ${conflicts.joinToString { it.filename ?: "Gatekeeperd self domain" }}")
                return@withLock NginxSiteActivationResult(false, message)
            }
            val availableFile = File("$sitesAvailablePath/$slug")
            val enabledFile = File("$sitesEnabledPath/$slug")
            if (isExcluded(availableFile, enabledFile)) {
                logger.warn("Refusing to manage nginx site $slug because its existing config serves Gatekeeperd")
                return@withLock NginxSiteActivationResult(false, "This config is excluded from Gatekeeperd management")
            }
            availableFile.parentFile?.mkdirs()
            enabledFile.parentFile?.mkdirs()

            val stagedFile = File(availableFile.parent, ".${slug}.staged")
            Files.deleteIfExists(stagedFile.toPath())
            Files.writeString(stagedFile.toPath(), configContent)

            // Validate the staged site through nginx's normal include tree while
            // leaving the live site and its enabled link untouched.
            val validation = testNginxConfigWithStagedSite(slug, stagedFile)
            if (!validation.valid) {
                Files.deleteIfExists(stagedFile.toPath())
                val detail = validation.output.trim().take(3000).ifBlank { "nginx -t exited with code ${validation.exitCode}" }
                logger.error("Nginx validation failed for staged site $slug (exit {}): {}", validation.exitCode, detail)
                return@withLock NginxSiteActivationResult(false, "nginx -t failed: $detail")
            }

            val backup = if (availableFile.isFile) {
                File(availableFile.parent, "$slug.bak-${Instant.now().toEpochMilli()}").also {
                    Files.copy(availableFile.toPath(), it.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                }
            } else null

            try {
                try {
                    Files.move(stagedFile.toPath(), availableFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(stagedFile.toPath(), availableFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                replaceEnabledSymlink(availableFile, enabledFile)

                if (!reloadNginx()) {
                    restoreActivatedSite(availableFile, enabledFile, backup)
                    reloadNginx()
                    logger.error("Nginx reload failed for $slug; previous configuration was restored")
                    return@withLock NginxSiteActivationResult(false, "Nginx reload failed after validation; the previous configuration was restored")
                }
            } catch (e: Exception) {
                restoreActivatedSite(availableFile, enabledFile, backup)
                runCatching { reloadNginx() }
                logger.error("Failed to activate nginx site $slug; previous configuration was restored", e)
                return@withLock NginxSiteActivationResult(false, "Nginx activation failed and the previous configuration was restored: ${e.message ?: "unknown error"}")
            } finally {
                Files.deleteIfExists(stagedFile.toPath())
            }

            // Read the activated file rather than hashing the input so drift
            // tracking always reflects the exact bytes now on disk.
            recordManagedVersion(slug, availableFile.readText())
            logger.info("Enabled nginx site: $slug")
            NginxSiteActivationResult(true)
          } catch (e: Exception) {
            logger.error("Failed to enable nginx site: $slug", e)
            NginxSiteActivationResult(false, "Failed to enable Nginx site: ${e.message ?: "unknown error"}")
          }
        }
    }

    /** Route a managed project site to a ready candidate and reload nginx before reporting success. */
    fun switchDeploymentUpstream(serviceId: java.util.UUID, slug: String, host: String, port: Int): Boolean {
        val site = SiteRepository.findByServiceId(serviceId) ?: return true
        val previousContent = inspectSite(slug).content
        val previousPort = site.upstreamExplicitPort
        val containerPort = site.upstreamContainerPort ?: port
        return switchDeploymentUpstream(serviceId, slug, host, containerPort, port)
    }

    fun switchDeploymentUpstream(serviceId: java.util.UUID, slug: String, host: String, containerPort: Int, hostPort: Int): Boolean {
        require(host == "127.0.0.1") { "Deployment upstream must use loopback" }
        require(containerPort in 1..65535 && hostPort in 1..65535) { "Deployment upstream ports must be between 1 and 65535" }
        val site = SiteRepository.findByServiceId(serviceId) ?: return true
        if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
            return DistributedLock.withLock("nginx-host-mutation") {
                val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
                val file = includes.file(serviceId)
                val previous = file.takeIf { it.isFile }?.readText()
                val candidate = includes.render(serviceId, hostPort)
                try {
                    includes.write(serviceId, candidate)
                    if (!nginxTestRunner().valid || !nginxReloadRunner() || !localProbeRunner(site.domain, site.tlsMode != TlsMode.HTTP_ONLY)) {
                        restoreUpstreamInclude(includes, serviceId, previous)
                        return@withLock false
                    }
                    true
                } catch (error: Exception) {
                    runCatching { restoreUpstreamInclude(includes, serviceId, previous) }
                    logger.error("Stable upstream cutover failed for service {}", serviceId, error)
                    false
                }
            }
        }
        val previousContent = inspectSite(slug).content
        val previousPort = site.upstreamExplicitPort
        val certificate = when (site.certMode) {
            CertMode.AUTO_RESOLVE -> resolveCertificateForDomain(site.domain)
            CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let {
                ResolvedCertificate("explicit", it, it.replace("fullchain.pem", "privkey.pem"))
            } ?: return false
        }
        val tls = when (site.tlsMode) {
            TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
            TlsMode.HTTPS -> TlsRenderMode.HTTPS
            TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
        }
        val model = NginxSiteRenderModel(
            slug = slug,
            projectId = site.projectId,
            serviceId = site.serviceId,
            siteId = site.id,
            domain = site.domain,
            upstreamHost = host,
            appPort = hostPort,
            upstreamScheme = if (containerPort == 443) "https" else "http",
            tlsMode = tls,
            certificatePath = certificate?.certificatePath,
            certificateKeyPath = certificate?.privateKeyPath,
            upstreamMode = UpstreamMode.EXPLICIT_PORT,
            certMode = site.certMode,
            gateEnabled = site.gateEnabled,
            bypassPaths = site.bypassPaths,
            customNginxLocations = site.customNginxLocations
        )
        val config = generateNginxConfig(model)
        if (!enableProject(slug, config)) return false
        if (SiteRepository.updateDeploymentUpstreamForSite(site.id, host, hostPort, containerPort) != null) return true
        previousContent?.let { restoreSiteConfiguration(slug, it) }
        previousPort?.let { SiteRepository.updateDeploymentUpstreamForSite(site.id, site.upstreamHost, it) }
        return false
    }

    /** One-site admin migration from an inline upstream to the stable service include. */
    fun convertSiteToDeploymentManaged(site: SiteRepository.SiteRecord, hostPort: Int): Boolean {
        val serviceId = site.serviceId ?: return false
        val slug = site.projectSlug ?: return false
        require(hostPort in 1..65535)
        return DistributedLock.withLock("nginx-host-mutation") {
            val previousSite = inspectSite(slug).content ?: return@withLock false
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val includeFile = includes.file(serviceId)
            val previousInclude = includeFile.takeIf { it.isFile }?.readText()
            val target = includes.render(serviceId, hostPort)
            val certificate = when (site.certMode) {
                CertMode.AUTO_RESOLVE -> resolveCertificateForDomain(site.domain)
                CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let { ResolvedCertificate("explicit", it, it.replace("fullchain.pem", "privkey.pem")) }
                    ?: return@withLock false
            }
            val model = NginxSiteRenderModel(
                slug = slug, projectId = site.projectId, serviceId = serviceId, siteId = site.id,
                domain = site.domain, upstreamHost = "127.0.0.1", appPort = site.upstreamExplicitPort ?: hostPort,
                upstreamScheme = "http",
                tlsMode = when (site.tlsMode) {
                    TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
                    TlsMode.HTTPS -> TlsRenderMode.HTTPS
                    TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
                }, certificatePath = certificate?.certificatePath, certificateKeyPath = certificate?.privateKeyPath,
                upstreamMode = site.upstreamMode, certMode = site.certMode, gateEnabled = site.gateEnabled,
                bypassPaths = site.bypassPaths, upstreamKind = "DEPLOYMENT_MANAGED",
                customNginxLocations = site.customNginxLocations
            )
            try {
                includes.write(serviceId, target)
                check(nginxTestRunner().valid) { "nginx -t failed with the service upstream include" }
                check(enableProject(slug, generateNginxConfig(model))) { "Unable to activate stable upstream site config" }
                check(localProbeRunner(site.domain, site.tlsMode != TlsMode.HTTP_ONLY)) { "Stable upstream probe failed after nginx reload" }
                check(SiteRepository.setUpstreamKind(site.id, "DEPLOYMENT_MANAGED") != null) { "Unable to persist deployment-managed site kind" }
                true
            } catch (error: Exception) {
                runCatching { restoreSiteConfiguration(slug, previousSite) }
                runCatching { restoreUpstreamInclude(includes, serviceId, previousInclude) }
                logger.error("Unable to convert site {} to deployment-managed upstream", site.id, error)
                false
            }
        }
    }

    fun restoreDeploymentUpstream(serviceId: java.util.UUID, port: Int?): Boolean =
        DistributedLock.withLock("nginx-host-mutation") {
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val previous = includes.file(serviceId).takeIf { it.isFile }?.readText()
            try {
                if (port == null) Files.deleteIfExists(includes.file(serviceId).toPath())
                else includes.write(serviceId, includes.render(serviceId, port))
                if (!nginxTestRunner().valid || !nginxReloadRunner()) {
                    restoreUpstreamInclude(includes, serviceId, previous)
                    return@withLock false
                }
                true
            } catch (error: Exception) {
                runCatching { restoreUpstreamInclude(includes, serviceId, previous) }
                logger.error("Unable to restore stable upstream for service {}", serviceId, error)
                false
            }
        }

    fun verifyDeploymentRoute(domain: String, tls: Boolean): Boolean =
        DistributedLock.withLock("nginx-host-mutation") { localProbeRunner(domain, tls) }

    data class PostDrainRouteCheck(val candidateHealthy: Boolean, val previousRouteRestored: Boolean)

    fun verifyAfterDrain(serviceId: java.util.UUID, domain: String, tls: Boolean, previousPort: Int?): PostDrainRouteCheck =
        DistributedLock.withLock("nginx-host-mutation") {
            if (localProbeRunner(domain, tls)) return@withLock PostDrainRouteCheck(true, false)
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            runCatching {
                if (previousPort == null) Files.deleteIfExists(includes.file(serviceId).toPath())
                else includes.write(serviceId, includes.render(serviceId, previousPort))
                check(nginxTestRunner().valid && nginxReloadRunner())
                check(localProbeRunner(domain, tls))
            }.fold(
                onSuccess = { PostDrainRouteCheck(false, true) },
                onFailure = { error ->
                    logger.error("Post-drain route failed and rollback was unsuccessful for service {}", serviceId, error)
                    PostDrainRouteCheck(false, false)
                }
            )
        }

    fun repairDeploymentUpstream(serviceId: java.util.UUID, domain: String, tls: Boolean, port: Int): Boolean =
        DistributedLock.withLock("nginx-host-mutation") {
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val previous = includes.file(serviceId).takeIf { it.isFile }?.readText()
            try {
                includes.write(serviceId, includes.render(serviceId, port))
                check(nginxTestRunner().valid && nginxReloadRunner())
                check(localProbeRunner(domain, tls))
                true
            } catch (error: Exception) {
                runCatching { restoreUpstreamInclude(includes, serviceId, previous) }
                logger.error("Explicit nginx upstream repair failed for service {}", serviceId, error)
                false
            }
        }

    fun recoverInterruptedUpstream(
        site: SiteRepository.SiteRecord,
        files: com.gatekeeper.deployment.DurableCutoverFiles,
        candidateRunning: Boolean
    ): InterruptedCutoverResult = DistributedLock.withLock("nginx-host-mutation") {
        val serviceId = site.serviceId ?: return@withLock InterruptedCutoverResult.CONFLICT
        val domain = site.domain
        val tls = site.tlsMode != TlsMode.HTTP_ONLY
        val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
        val file = includes.file(serviceId)
        val current = file.takeIf { it.isFile }?.readText()
        val pending = files.pendingIncludeContent ?: return@withLock InterruptedCutoverResult.CONFLICT
        val currentHash = current?.let(includes::checksum)
        val pendingMatches = currentHash == files.pendingChecksum && files.pendingChecksum == includes.checksum(pending)
        val previousMatches = current == files.previousIncludeContent
        if (pendingMatches && candidateRunning) {
            val restored = if (site.upstreamKind != "DEPLOYMENT_MANAGED") {
                val targetPort = includePort(pending) ?: return@withLock InterruptedCutoverResult.CONFLICT
                convertSiteToDeploymentManaged(site, targetPort)
            } else {
                nginxTestRunner().valid && nginxReloadRunner() && localProbeRunner(domain, tls)
            }
            if (restored) {
                return@withLock InterruptedCutoverResult.TARGET_RELOADED
            }
        } else if (previousMatches) {
            if (files.previousUpstreamKind != "DEPLOYMENT_MANAGED") {
                val slug = files.siteSlug ?: site.projectSlug
                val configRestored = if (files.previousSiteConfig != null && slug != null) {
                    restoreSiteConfiguration(slug, files.previousSiteConfig)
                } else true
                if (!configRestored) return@withLock InterruptedCutoverResult.CONFLICT
                if (SiteRepository.restoreUpstreamKind(site.id, files.previousUpstreamKind) == null) {
                    return@withLock InterruptedCutoverResult.CONFLICT
                }
            }
            return@withLock InterruptedCutoverResult.PREVIOUS_RESTORED
        }
        if (!pendingMatches) return@withLock InterruptedCutoverResult.CONFLICT
        runCatching {
            if (files.previousUpstreamKind != "DEPLOYMENT_MANAGED") {
                val slug = files.siteSlug ?: site.projectSlug
                check(files.previousSiteConfig != null && slug != null)
                check(restoreSiteConfiguration(slug, files.previousSiteConfig))
                check(SiteRepository.restoreUpstreamKind(site.id, files.previousUpstreamKind) != null)
            }
            if (files.previousIncludeContent == null) Files.deleteIfExists(file.toPath())
            else includes.write(serviceId, files.previousIncludeContent)
            check(nginxTestRunner().valid && nginxReloadRunner())
            check(localProbeRunner(domain, tls))
        }.fold(
            onSuccess = { InterruptedCutoverResult.PREVIOUS_RESTORED },
            onFailure = { error ->
                logger.error("Interrupted nginx cutover could not restore service {}", serviceId, error)
                InterruptedCutoverResult.CONFLICT
            }
        )
    }

    private fun includePort(content: String): Int? = Regex("server\\s+127\\.0\\.0\\.1:(\\d+);?")
        .find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun restoreUpstreamInclude(includes: DeploymentUpstreamIncludes, serviceId: java.util.UUID, previous: String?) {
        if (previous == null) Files.deleteIfExists(includes.file(serviceId).toPath()) else includes.write(serviceId, previous)
        check(nginxTestRunner().valid && nginxReloadRunner()) { "Unable to restore the previous nginx upstream include" }
    }

    private fun replaceEnabledSymlink(availableFile: File, enabledFile: File) {
        Files.deleteIfExists(enabledFile.toPath())
        Files.createSymbolicLink(enabledFile.toPath(), availableFile.toPath())
    }

    private fun restoreActivatedSite(availableFile: File, enabledFile: File, backup: File?) {
        Files.deleteIfExists(enabledFile.toPath())
        if (backup?.isFile == true) {
            Files.copy(backup.toPath(), availableFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            replaceEnabledSymlink(availableFile, enabledFile)
        } else {
            Files.deleteIfExists(availableFile.toPath())
        }
    }

    private fun testNginxConfigWithStagedSite(slug: String, stagedFile: File): NginxTestResult {
        // Keep the name visible to nginx's usual `sites-enabled/*` include glob.
        val stagedLink = File(sitesEnabledPath, "gatekeeperd-staged-$slug-${System.nanoTime()}")
        return try {
            Files.createSymbolicLink(stagedLink.toPath(), stagedFile.toPath())
            testNginxConfigDetailed()
        } finally {
            Files.deleteIfExists(stagedLink.toPath())
        }
    }

    fun disableProject(slug: String): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") { try {
            val enabledFile = File("$sitesEnabledPath/$slug")
            if (isExcluded(File(sitesAvailablePath, slug), enabledFile)) {
                logger.warn("Refusing to disable nginx site $slug because it serves Gatekeeperd")
                return@withLock false
            }
            if (enabledFile.exists()) {
                enabledFile.delete()
                logger.info("Disabled nginx site: $slug")
            }
            true
        } catch (e: Exception) {
            logger.error("Failed to disable nginx site: $slug", e)
            false
        } }
    }

    fun removeProject(slug: String): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") { try {
            val availableFile = File("$sitesAvailablePath/$slug")
            val enabledFile = File("$sitesEnabledPath/$slug")
            if (isExcluded(availableFile, enabledFile)) {
                logger.warn("Refusing to remove nginx site $slug because it serves Gatekeeperd")
                return@withLock false
            }

            if (enabledFile.exists()) {
                enabledFile.delete()
            }
            if (availableFile.exists()) {
                availableFile.delete()
            }
            File(sitesAvailablePath, ".$slug.gatekeeperd.sha256").delete()

            logger.info("Removed nginx site: $slug")
            true
        } catch (e: Exception) {
            logger.error("Failed to remove nginx site: $slug", e)
            false
        } }
    }

    fun testNginxConfig(): Boolean {
        return testNginxConfigDetailed().valid
    }

    fun reloadNginx(): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") {
            try {
                val testPassed = testNginxConfig()
                if (!testPassed) return@withLock false
                if (!nginxReloadRunner()) return@withLock false
                logger.info("Nginx reloaded successfully")
                true
            } catch (e: Exception) {
                logger.error("Failed to reload nginx", e)
                false
            }
        }
    }

    private fun isExcluded(vararg files: File): Boolean = files.any(selfDomainExclusion::excludesFile)

    fun isCertbotAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "certbot"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun certificateManagerIssue(): String? {
        if (!isCertbotAvailable()) return "Certbot is not installed or is not available in the Gatekeeperd service PATH."
        val helper = File("/usr/local/sbin/gatekeeperd-certbot")
        if (!helper.isFile || !helper.canExecute()) {
            return "The restricted Gatekeeperd Certbot helper is missing. Redeploy with the current deploy.sh to install it."
        }
        return try {
            val process = ProcessBuilder("sudo", "-n", "-l", helper.absolutePath)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Timed out checking Gatekeeperd's Certbot permissions."
            } else if (process.exitValue() != 0) {
                process.inputStream.close()
                "The Gatekeeperd service account cannot run the Certbot helper. Redeploy to configure its restricted sudoers rule."
            } else {
                process.inputStream.close()
                null
            }
        } catch (e: Exception) {
            logger.warn("Could not verify Certbot helper permissions", e)
            "Could not verify Gatekeeperd's Certbot permissions. Check the service sudoers configuration."
        }
    }

    private fun runCertbotHelper(vararg arguments: String): Int? {
        val process = ProcessBuilder(
            listOf("sudo", "-n", "/usr/local/sbin/gatekeeperd-certbot") + arguments
        )
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        if (!process.waitFor(8, java.util.concurrent.TimeUnit.MINUTES)) {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            logger.error("Certbot helper timed out after 8 minutes: ${arguments.firstOrNull() ?: "unknown operation"}")
            return null
        }
        return process.exitValue()
    }

    fun installCertificate(domain: String, email: String): Boolean {
        requireValidHostname(domain)
        requireValidEmail(email)
        certificateManagerIssue()?.let { issue ->
            logger.error("Certificate manager unavailable: $issue")
            return false
        }

        return DistributedLock.withLock("nginx-host-mutation") { try {
            val exitCode = runCertbotHelper("install", domain, email)

            if (exitCode != 0) {
                logger.error("Certbot install failed for domain $domain (exit=${exitCode ?: "timeout"})")
                return@withLock false
            }

            logger.info("SSL certificate installed for domain: $domain")
            true
        } catch (e: Exception) {
            logger.error("Failed to install SSL certificate for $domain", e)
            false
        } }
    }

    fun renewCertificate(domain: String): Boolean {
        requireValidHostname(domain)
        if (!isCertificateInstalled(domain) || certificateManagerIssue() != null) return false

        return DistributedLock.withLock("nginx-host-mutation") { try {
            val exitCode = runCertbotHelper("renew", domain)
            if (exitCode != 0) {
                logger.error("Certbot renewal failed for domain $domain (exit=${exitCode ?: "timeout"})")
                return@withLock false
            }
            if (!reloadNginx()) {
                logger.error("Nginx reload failed after Certbot renewal for domain $domain")
                return@withLock false
            }
            logger.info("Certbot renewal check completed for domain: $domain")
            true
        } catch (e: Exception) {
            logger.error("Failed to renew certificate for $domain", e)
            false
        } }
    }

    fun removeCertificate(domain: String): Boolean {
        requireValidHostname(domain)
        if (selfDomainExclusion.excludesDomain(domain)) {
            logger.warn("Refusing to remove certificate $domain because it covers a Gatekeeperd self domain")
            return false
        }
        certificateManagerIssue()?.let { issue ->
            logger.error("Cannot remove certificate for $domain: $issue")
            return false
        }

        return DistributedLock.withLock("nginx-host-mutation") { try {
            val exitCode = runCertbotHelper("remove", domain)

            if (exitCode != 0) {
                logger.error("Certbot delete failed for domain $domain (exit=${exitCode ?: "timeout"})")
                return@withLock false
            }

            logger.info("SSL certificate removed for domain: $domain")
            true
        } catch (e: Exception) {
            logger.error("Failed to remove SSL certificate for $domain", e)
            false
        } }
    }

    fun isCertificateInstalled(domain: String): Boolean {
        requireValidHostname(domain)
        val certDir = File("$sslCertPath/$domain")
        return certDir.exists() &&
                File(certDir, "fullchain.pem").exists() &&
                File(certDir, "privkey.pem").exists()
    }

    fun resolveCertificateForDomain(domain: String, requestedCertificateDomain: String? = null): ResolvedCertificate? {
        requireValidHostname(domain)
        val requested = requestedCertificateDomain?.trim()?.takeIf { it.isNotBlank() }
        requested?.let { requireValidHostname(it, "certificateDomain") }
        if (requested != null) {
            return resolveInstalledCertificateByName(requested)
        }

        resolveInstalledCertificateByName(domain)?.let { return it }

        val parents = parentDomainCandidates(domain)
        for (candidate in parents) {
            resolveInstalledCertificateByName(candidate)?.let { return it }
        }

        return null
    }

    fun listInstalledCertificates(): List<ResolvedCertificate> {
        val liveDir = File(sslCertPath)
        if (!liveDir.exists() || !liveDir.isDirectory) return emptyList()

        return liveDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val fullchain = File(dir, "fullchain.pem")
                val privkey = File(dir, "privkey.pem")
                if (!fullchain.exists() || !privkey.exists()) return@mapNotNull null
                ResolvedCertificate(
                    certificateDomain = dir.name,
                    certificatePath = fullchain.absolutePath,
                    privateKeyPath = privkey.absolutePath
                )
            }
            .sortedBy { it.certificateDomain }
    }

    private fun resolveInstalledCertificateByName(certificateDomain: String): ResolvedCertificate? {
        if (!isCertificateInstalled(certificateDomain)) return null
        return ResolvedCertificate(
            certificateDomain = certificateDomain,
            certificatePath = "$sslCertPath/$certificateDomain/fullchain.pem",
            privateKeyPath = "$sslCertPath/$certificateDomain/privkey.pem"
        )
    }

    private fun parentDomainCandidates(domain: String): List<String> {
        val parts = domain.trim().trimEnd('.').split(".").filter { it.isNotBlank() }
        if (parts.size <= 2) return emptyList()

        val candidates = mutableListOf<String>()
        for (start in 1..(parts.size - 2)) {
            candidates.add(parts.subList(start, parts.size).joinToString("."))
        }
        return candidates
    }
}

internal fun nginxIncludesDirectoryInHttpContext(configDump: String, directory: String): Boolean {
    val expected = "${File(directory).absolutePath.trimEnd('/') }/*.conf"
    val withoutComments = configDump.lineSequence().map { it.substringBefore('#') }.joinToString("\n")
    val httpStart = Regex("\\bhttp\\s*\\{").find(withoutComments) ?: return false
    var depth = 1
    var index = httpStart.range.last + 1
    val bodyStart = index
    while (index < withoutComments.length && depth > 0) {
        when (withoutComments[index]) {
            '{' -> depth++
            '}' -> depth--
        }
        if (depth == 1 && withoutComments[index] == ';') {
            val statement = withoutComments.substring(bodyStart.coerceAtMost(index), index + 1)
            if (Regex("\\binclude\\s+${Regex.escape(expected)}\\s*;").containsMatchIn(statement)) return true
        }
        index++
    }
    return false
}
