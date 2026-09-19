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

private val logger = LoggerFactory.getLogger("com.gatekeeper.nginx.NginxService")

data class ResolvedCertificate(
    val certificateDomain: String,
    val certificatePath: String,
    val privateKeyPath: String
)

class NginxService(
    private val sitesAvailablePath: String = AppConfig.nginxSitesAvailablePath,
    private val sitesEnabledPath: String = AppConfig.nginxSitesEnabledPath,
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = AppConfig.nginxSslCertPath
) {
    fun inspectSite(slug: String): NginxConfigInspection {
        require(slug.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid nginx site name" }
        val availableFile = File(sitesAvailablePath, slug)
        val enabledFile = File(sitesEnabledPath, slug)
        val content = availableFile.takeIf { it.isFile }?.readText()
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
        )
    }

    private fun extractBlocks(content: String): List<NginxConfigBlock> {
        val blocks = mutableListOf<NginxConfigBlock>()
        val pattern = Regex("(?m)^\\s*(server|location(?:\\s*=|\\s+|\\s+@)[^\\{]*)\\s*\\{")
        pattern.findAll(content).forEach { match ->
            var depth = 0
            var end = -1
            for (index in match.range.last until content.length) {
                when (content[index]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = index + 1; break } } }
            }
            if (end > 0) blocks += NginxConfigBlock(match.groupValues[1].trim().substringBefore('{').trim().split(Regex("\\s+"), 2).first(), match.groupValues[1].trim(), content.substring(match.range.first, end))
        }
        return blocks
    }

    fun testNginxConfigDetailed(): NginxTestResult {
        return try {
            val process = ProcessBuilder("sudo", "/usr/sbin/nginx", "-t").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            NginxTestResult(exitCode == 0, exitCode, output, OffsetDateTime.now().toString())
        } catch (e: Exception) {
            NginxTestResult(false, -1, e.message ?: "Failed to execute nginx -t", OffsetDateTime.now().toString())
        }
    }

    fun previewBlockUpdate(slug: String, blockIndex: Int, replacement: String): String {
        val current = inspectSite(slug)
        require(current.available) { "Nginx site '$slug' does not exist" }
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
        val current = File(sitesAvailablePath, "$slug.bak-${Instant.now().toEpochMilli()}")
        if (target.isFile) Files.copy(target.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val validation = testNginxConfigDetailed()
        if (!validation.valid) {
            if (current.isFile) Files.copy(current.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return NginxRollbackResponse(false, "Rollback validation failed; previous configuration restored", validation)
        }
        if (!reloadNginx()) {
            if (current.isFile) Files.copy(current.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            reloadNginx()
            return NginxRollbackResponse(false, "Rollback reload failed; previous configuration restored", validation)
        }
        return NginxRollbackResponse(true, "Configuration rolled back and Nginx reloaded successfully", validation, true)
    }

    fun certificateExpiry(domain: String): Pair<String, Long>? {
        return try {
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
        slug: String,
        domain: String,
        appPort: Int,
        upstreamScheme: String? = null,
        sslEnabled: Boolean,
        sslCertificatePath: String? = null,
        sslCertificateKeyPath: String? = null
    ): String {
        val effectiveSslCert = sslCertificatePath ?: "$sslCertPath/$domain/fullchain.pem"
        val effectiveSslKey = sslCertificateKeyPath ?: "$sslCertPath/$domain/privkey.pem"
        val effectiveUpstreamScheme = normalizeUpstreamScheme(appPort, upstreamScheme)

        return buildString {
            appendLine("server {")
            if (sslEnabled) {
                appendLine("    listen 443 ssl;")
                appendLine("    listen [::]:443 ssl;")
                appendLine("    http2 on;")
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

            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 1. Gatekeeper Direct Bypass Route")
            appendLine("    # Payment callbacks, webhooks, and paywall APIs bypass auth_request")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    location /api/gate/ {")
                appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort/api/gate/;")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            appendLine()

            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 2. Main Protected Application Route")
            appendLine("    # Evaluates gatekeeper auth_request on every incoming request")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    location / {")
                appendLine("        auth_request /gatekeeper-auth-$slug;")
                appendLine("        error_page 403 = @gatekeeper_paywall_$slug;")
                appendLine()
                appendLine("        proxy_pass $effectiveUpstreamScheme://127.0.0.1:$appPort;")
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

            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 3. Isolated Gatekeeper Subrequest (Browser-Header Sanitizer)")
            appendLine("    # Strips all browser headers, CORS metadata, and POST bodies")
            appendLine("    # -------------------------------------------------------------------------")
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

            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 4. Paywall Fallback Location")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    location @gatekeeper_paywall_$slug {")
            appendLine("        rewrite ^ /api/gate/paywall?project=$slug break;")
            appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort;")
            appendLine("        proxy_set_header Host \$host;")
            appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
            appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
            appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
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
        return try {
            val availableFile = File("$sitesAvailablePath/$slug")
            val enabledFile = File("$sitesEnabledPath/$slug")
            availableFile.parentFile?.mkdirs()
            enabledFile.parentFile?.mkdirs()

            if (availableFile.exists()) {
                val backup = File(availableFile.parent, "$slug.bak-${Instant.now().toEpochMilli()}")
                Files.copy(availableFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            }

            val temporaryFile = File(availableFile.parent, ".$slug.tmp-${System.nanoTime()}")
            Files.writeString(temporaryFile.toPath(), configContent)
            try {
                Files.move(
                    temporaryFile.toPath(),
                    availableFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporaryFile.toPath(), availableFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            if (enabledFile.exists()) {
                Files.delete(enabledFile.toPath())
            }

            val result = Runtime.getRuntime().exec(
                arrayOf("ln", "-s", availableFile.absolutePath, enabledFile.absolutePath),
                null
            )
            result.waitFor()

            if (result.exitValue() != 0) {
                val error = result.errorStream.bufferedReader().readText()
                logger.error("Failed to create symlink for $slug: $error")
                return false
            }

            logger.info("Enabled nginx site: $slug")
            true
        } catch (e: Exception) {
            logger.error("Failed to enable nginx site: $slug", e)
            false
        }
    }

    fun disableProject(slug: String): Boolean {
        return try {
            val enabledFile = File("$sitesEnabledPath/$slug")
            if (enabledFile.exists()) {
                enabledFile.delete()
                logger.info("Disabled nginx site: $slug")
            }
            true
        } catch (e: Exception) {
            logger.error("Failed to disable nginx site: $slug", e)
            false
        }
    }

    fun restoreLatestBackup(slug: String): Boolean {
        return try {
            val availableFile = File("$sitesAvailablePath/$slug")
            val backup = availableFile.parentFile?.listFiles { file ->
                file.name.startsWith("$slug.bak-")
            }?.maxByOrNull { it.lastModified() }
                ?: return false
            Files.copy(backup.toPath(), availableFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.info("Restored nginx site $slug from backup ${backup.name}")
            true
        } catch (e: Exception) {
            logger.error("Failed to restore nginx backup for $slug", e)
            false
        }
    }

    fun removeProject(slug: String): Boolean {
        return try {
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
        }
    }

    fun testNginxConfig(): Boolean {
        return try {
            val process = ProcessBuilder("sudo", "/usr/sbin/nginx", "-t").start()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                logger.error("Nginx configuration test failed: $error")
                return false
            }
            true
        } catch (e: Exception) {
            logger.error("Failed to test Nginx config", e)
            false
        }
    }

    fun reloadNginx(): Boolean {
        return try {
            val testPassed = testNginxConfig()
            if (!testPassed) {
                return false
            }

            // Executing with 'sudo' so sudoers NOPASSWD kicks in!
            val process = ProcessBuilder("sudo", "/bin/systemctl", "reload", "nginx").start()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                logger.error("Failed to reload nginx: $error")
                return false
            }

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
        if (!isCertbotAvailable()) {
            logger.error("Certbot is not installed on this system")
            return false
        }

        return try {
            val cmd = arrayOf(
                "certbot", "certonly", "--nginx",
                "--non-interactive", "--agree-tos",
                "-d", domain,
                "-m", email
            )

            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                logger.error("Certbot failed for domain $domain: $error")
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
        if (!isCertbotAvailable()) {
            logger.warn("Certbot is not installed, skipping certificate removal for $domain")
            return true
        }

        return try {
            val cmd = arrayOf("certbot", "delete", "--cert-name", domain, "-n")

            val process = Runtime.getRuntime().exec(cmd)
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                logger.error("Certbot delete failed for domain $domain: $error")
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
        val certDir = File("$sslCertPath/$domain")
        return certDir.exists() &&
                File(certDir, "fullchain.pem").exists() &&
                File(certDir, "privkey.pem").exists()
    }

    fun resolveCertificateForDomain(domain: String, requestedCertificateDomain: String? = null): ResolvedCertificate? {
        val requested = requestedCertificateDomain?.trim()?.takeIf { it.isNotBlank() }
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
