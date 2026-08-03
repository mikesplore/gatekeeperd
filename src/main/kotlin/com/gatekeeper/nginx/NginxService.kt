package com.gatekeeper.nginx

import com.gatekeeper.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private val logger = LoggerFactory.getLogger("com.gatekeeper.nginx.NginxService")

class NginxService(
    private val sitesAvailablePath: String = AppConfig.nginxSitesAvailablePath,
    private val sitesEnabledPath: String = AppConfig.nginxSitesEnabledPath,
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = AppConfig.nginxSslCertPath
) {

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

            appendLine("    # Route gatekeeper payment pages through the client domain")
            appendLine("    location /api/gate/ {")
            appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort/api/gate/;")
            appendLine("        proxy_set_header Host \$host;")
            appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
            appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
            appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            appendLine()

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

            appendLine("    location = /gatekeeper-auth-$slug {")
            appendLine("        internal;")
            appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort/api/gate/auth?project=$slug;")
            appendLine("        proxy_pass_request_body off;")
            appendLine()
            appendLine("        proxy_http_version 1.1;")
            appendLine("        proxy_set_header Connection \"\";")
            appendLine("        proxy_set_header Content-Length \"\";")
            appendLine("        proxy_set_header X-Original-URI \$request_uri;")
            appendLine("    }")
            appendLine()

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

            availableFile.writeText(configContent)

            if (enabledFile.exists()) {
                enabledFile.delete()
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
}
