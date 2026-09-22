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
import java.time.OffsetDateTime
import java.security.MessageDigest
import com.gatekeeper.plugins.DistributedLock

private val logger = LoggerFactory.getLogger("com.gatekeeper.nginx.NginxService")

private fun runNginxTestCommand(): NginxTestResult {
    return try {
        val process = ProcessBuilder("sudo", "-n", "/usr/sbin/nginx", "-t").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        NginxTestResult(exitCode == 0, exitCode, output, OffsetDateTime.now().toString())
    } catch (e: Exception) {
        NginxTestResult(false, -1, "Unable to execute nginx -t non-interactively: ${e.message ?: "unknown error"}. Configure sudoers NOPASSWD for the Gatekeeperd service account.", OffsetDateTime.now().toString())
    }
}

private fun runNginxReloadCommand(): Boolean {
    return try {
        val process = ProcessBuilder("sudo", "-n", "/bin/systemctl", "reload", "nginx").redirectErrorStream(true).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) logger.error("Failed to reload nginx: ${process.inputStream.bufferedReader().readText()}")
        exitCode == 0
    } catch (e: Exception) {
        logger.error("Failed to reload nginx", e)
        false
    }
}

data class ResolvedCertificate(
    val certificateDomain: String,
    val certificatePath: String,
    val privateKeyPath: String
)

class NginxService(
    private val sitesAvailablePath: String = AppConfig.nginxSitesAvailablePath,
    private val sitesEnabledPath: String = AppConfig.nginxSitesEnabledPath,
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = AppConfig.nginxSslCertPath,
    private val nginxTestRunner: () -> NginxTestResult = ::runNginxTestCommand,
    private val nginxReloadRunner: () -> Boolean = ::runNginxReloadCommand
) {
    fun inspectSite(slug: String): NginxConfigInspection {
        require(slug.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid nginx site name" }
        val availableFile = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
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
            modifiedAt = availableFile.takeIf { it.exists() }?.let { Instant.ofEpochMilli(it.lastModified()).toString() },
            sizeBytes = availableFile.takeIf { it.exists() }?.length()
            ,managed = content?.contains("# gatekeeperd:block:") == true,
            manual = content != null && !content.contains("# gatekeeperd:block:"),
            drifted = managedHash != null && actualHash != managedHash,
            actualSha256 = actualHash,
            managedSha256 = managedHash
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun recordManagedVersion(slug: String, content: String) {
        Files.writeString(File(sitesAvailablePath, ".$slug.gatekeeperd.sha256").toPath(), sha256(content))
    }

    private fun extractBlocks(content: String): List<NginxConfigBlock> {
        val marker = Regex("(?m)^\\s*# gatekeeperd:block:([a-z_]+)\\s*$")
        val markers = marker.findAll(content).toList()
        if (markers.isEmpty()) return emptyList()
        return markers.mapIndexed { index, match ->
            val name = match.groupValues[1]
            val start = match.range.first
            val end = if (name == "server") content.length else markers.getOrNull(index + 1)?.range?.first ?: content.length
            NginxConfigBlock(name, "# gatekeeperd:block:$name", content.substring(start, end).trimEnd())
        }
    }

    fun testNginxConfigDetailed(): NginxTestResult {
        return nginxTestRunner()
    }

    fun previewBlockUpdate(slug: String, blockIndex: Int, replacement: String): String {
        val current = inspectSite(slug)
        require(current.available) { "Nginx site '$slug' does not exist" }
        require(!current.manual) { "Nginx site '$slug' is manual/unmanaged; edit the file directly. Gatekeeperd will only detect drift." }
        require(blockIndex in current.blocks.indices) { "Block index $blockIndex is out of range" }
        val block = current.blocks[blockIndex]
        val start = current.content!!.indexOf(block.content)
        require(start >= 0) { "Could not locate selected block in the live configuration" }
        return current.content.replaceRange(start, start + block.content.length, replacement)
    }

    fun applyBlockUpdate(slug: String, blockIndex: Int, replacement: String): NginxBlockUpdateResponse {
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
        return File(sitesAvailablePath).listFiles { file -> file.name.startsWith("$slug.bak-") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { NginxBackup(it.name, Instant.ofEpochMilli(it.lastModified()).toString(), it.length()) }
            .orEmpty()
    }

    fun rollback(slug: String, backupName: String): NginxRollbackResponse {
        require(backupName.matches(Regex("${Regex.escape(slug)}\\.bak-[0-9]+"))) { "Invalid backup name" }
        val backup = File(sitesAvailablePath, backupName)
        require(backup.isFile) { "Backup does not exist" }
        val target = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
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
        } catch (e: Exception) {
            restoreActivatedSite(target, enabledFile, current)
            runCatching { reloadNginx() }
            return NginxRollbackResponse(false, "Rollback activation failed; previous configuration was restored", validation)
        } finally {
            Files.deleteIfExists(stagedFile.toPath())
        }
        recordManagedVersion(slug, target.readText())
        return NginxRollbackResponse(true, "Configuration rolled back and Nginx reloaded successfully", validation, true)
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
        } catch (e: Exception) {
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
        bypassPaths = site.bypassPaths
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
        bypassPaths: List<String> = DEFAULT_GATEKEEPER_BYPASS_PATHS
    ): String {
        requireValidHostname(domain)
        val effectiveSslCert = sslCertificatePath?.let { requireCertificatePath(it, sslCertPath) }
            ?: "$sslCertPath/$domain/fullchain.pem"
        val effectiveSslKey = sslCertificateKeyPath?.let { requireCertificatePath(it, sslCertPath) }
            ?: "$sslCertPath/$domain/privkey.pem"
        val effectiveUpstreamScheme = normalizeUpstreamScheme(appPort, upstreamScheme)

        return buildString {
            appendLine("# gatekeeperd:block:server")
            appendLine("server {")
            if (sslEnabled) {
                appendLine("    listen 443 ssl;")
                appendLine("    listen [::]:443 ssl;")
                if (http2) appendLine("    http2 on;")
                appendLine()
                appendLine("    server_name $domain;")
                appendLine()
                appendLine("    ssl_certificate $effectiveSslCert;")
                appendLine("    ssl_certificate_key $effectiveSslKey;")
                appendLine()
            } else {
                appendLine("    listen 80;")
                appendLine("    listen [::]:80;")
                appendLine()
                appendLine("    server_name $domain;")
                appendLine()
            }

            if (gateEnabled) {
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 1. Gatekeeper Direct Bypass Route")
            appendLine("    # Payment callbacks, webhooks, and paywall APIs bypass auth_request")
            appendLine("    # -------------------------------------------------------------------------")
            bypassPaths.forEach { path ->
                require(path.startsWith("/") && path.endsWith("/") && !path.contains(';')) { "Invalid bypass path" }
                if (path == "/api/paystack/") appendLine("    # Payment provider callbacks must not be gated by the client project")
                appendLine("    location $path {")
                appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort$path;")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
                appendLine("    }")
                appendLine()
            }
            }

            appendLine("    # -------------------------------------------------------------------------")
            appendLine(if (gateEnabled) "    # 2. Main Protected Application Route" else "    # 2. Main Application Route")
            if (gateEnabled) appendLine("    # Evaluates gatekeeper auth_request on every incoming request")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # gatekeeperd:block:upstream")
            appendLine("    location / {")
                if (gateEnabled) {
                    appendLine("        auth_request /gatekeeper-auth-$slug;")
                    appendLine("        error_page 403 = @gatekeeper_paywall_$slug;")
                }
                appendLine()
                appendLine("        proxy_pass $effectiveUpstreamScheme://$upstreamHost:$appPort;")
                appendLine("        proxy_http_version 1.1;")
                appendLine()
                appendLine("        proxy_set_header Upgrade \$http_upgrade;")
                appendLine("        proxy_set_header Connection \"upgrade\";")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            appendLine()

            if (gateEnabled) {
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 3. Isolated Gatekeeper Subrequest (Browser-Header Sanitizer)")
            appendLine("    # Strips all browser headers, CORS metadata, and POST bodies")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # gatekeeperd:block:auth_request")
            appendLine("    location = /gatekeeper-auth-$slug {")
                appendLine("        internal;")
                appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort/api/gate/auth?project=$slug;")
                appendLine()
                appendLine("        # Always force subrequest method to GET")
                appendLine("        proxy_method GET;")
                appendLine()
                appendLine("        # Disable body forwarding")
                appendLine("        proxy_pass_request_body off;")
                appendLine()
                appendLine("        # Strip all body and content headers")
                appendLine("        proxy_set_header Content-Length \"\";")
                appendLine("        proxy_set_header Content-Type \"\";")
                appendLine("        proxy_set_header Transfer-Encoding \"\";")
                appendLine()
                appendLine("        # Strip browser CORS and metadata headers that trigger 0ms 403s in Ktor")
                appendLine("        proxy_set_header Authorization \"\";")
                appendLine("        proxy_set_header Cookie \"\";")
                appendLine("        proxy_set_header Origin \"\";")
                appendLine("        proxy_set_header Referer \"\";")
                appendLine("        proxy_set_header User-Agent \"Nginx-Auth-Check\";")
                appendLine("        proxy_set_header Accept \"\";")
                appendLine("        proxy_set_header Accept-Encoding \"\";")
                appendLine("        proxy_set_header Accept-Language \"\";")
                appendLine("        proxy_set_header Sec-Fetch-Dest \"\";")
                appendLine("        proxy_set_header Sec-Fetch-Mode \"\";")
                appendLine("        proxy_set_header Sec-Fetch-Site \"\";")
                appendLine("        proxy_set_header Sec-Ch-Ua \"\";")
                appendLine()
                appendLine("        # Point Host header strictly to 127.0.0.1")
                appendLine("        proxy_set_header Host 127.0.0.1;")
                appendLine()
                appendLine("        proxy_http_version 1.1;")
                appendLine("        proxy_set_header Connection \"\";")
            appendLine("    }")
            appendLine()
            }

            if (gateEnabled) {
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 4. Paywall Fallback Location")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # gatekeeperd:block:paywall")
            appendLine("    location @gatekeeper_paywall_$slug {")
            appendLine("        rewrite ^ /api/gate/paywall?project=$slug break;")
            appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort;")
            appendLine("        proxy_set_header Host \$host;")
            appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
            appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
            appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            }
            appendLine("}")
        }
    }

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
        return DistributedLock.withLock("nginx-site:$slug") {
          try {
            val availableFile = File("$sitesAvailablePath/$slug")
            val enabledFile = File("$sitesEnabledPath/$slug")
            availableFile.parentFile?.mkdirs()
            enabledFile.parentFile?.mkdirs()

            val stagedFile = File(availableFile.parent, ".${slug}.staged")
            Files.deleteIfExists(stagedFile.toPath())
            Files.writeString(stagedFile.toPath(), configContent)

            // Validate the staged site through nginx's normal include tree while
            // leaving the live site and its enabled link untouched.
            if (!testNginxConfigWithStagedSite(slug, stagedFile).valid) {
                Files.deleteIfExists(stagedFile.toPath())
                logger.error("Nginx validation failed for staged site $slug; live configuration was not changed")
                return@withLock false
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
                    return@withLock false
                }
            } catch (e: Exception) {
                restoreActivatedSite(availableFile, enabledFile, backup)
                runCatching { reloadNginx() }
                logger.error("Failed to activate nginx site $slug; previous configuration was restored", e)
                return@withLock false
            } finally {
                Files.deleteIfExists(stagedFile.toPath())
            }

            // Read the activated file rather than hashing the input so drift
            // tracking always reflects the exact bytes now on disk.
            recordManagedVersion(slug, availableFile.readText())
            logger.info("Enabled nginx site: $slug")
            true
          } catch (e: Exception) {
            logger.error("Failed to enable nginx site: $slug", e)
            false
          }
        }
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
        return DistributedLock.withLock("nginx-site:$slug") { try {
            val enabledFile = File("$sitesEnabledPath/$slug")
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
        return DistributedLock.withLock("nginx-site:$slug") { try {
            val availableFile = File("$sitesAvailablePath/$slug")
            val enabledFile = File("$sitesEnabledPath/$slug")

            if (enabledFile.exists()) {
                enabledFile.delete()
            }
            if (availableFile.exists()) {
                availableFile.delete()
            }

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
        return try {
            val testPassed = testNginxConfig()
            if (!testPassed) {
                return false
            }

            if (!nginxReloadRunner()) return false
            logger.info("Nginx reloaded successfully")
            true
        } catch (e: Exception) {
            logger.error("Failed to reload nginx", e)
            false
        }
    }

    fun isCertbotAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "certbot"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun installCertificate(domain: String, email: String): Boolean {
        requireValidHostname(domain)
        requireValidEmail(email)
        if (!isCertbotAvailable()) {
            logger.error("Certbot is not installed on this system")
            return false
        }

        return try {
            val process = ProcessBuilder(
                "sudo", "-n", "/usr/local/sbin/gatekeeperd-certbot",
                "install", domain, email
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.error("Certbot failed for domain $domain (exit=$exitCode): $output")
                return false
            }

            logger.info("SSL certificate installed for domain: $domain")
            true
        } catch (e: Exception) {
            logger.error("Failed to install SSL certificate for $domain", e)
            false
        }
    }

    fun removeCertificate(domain: String): Boolean {
        requireValidHostname(domain)
        if (!isCertbotAvailable()) {
            logger.warn("Certbot is not installed, skipping certificate removal for $domain")
            return true
        }

        return try {
            val process = ProcessBuilder(
                "sudo", "-n", "/usr/local/sbin/gatekeeperd-certbot",
                "remove", domain
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                logger.error("Certbot delete failed for domain $domain (exit=$exitCode): $output")
                return false
            }

            logger.info("SSL certificate removed for domain: $domain")
            true
        } catch (e: Exception) {
            logger.error("Failed to remove SSL certificate for $domain", e)
            false
        }
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
