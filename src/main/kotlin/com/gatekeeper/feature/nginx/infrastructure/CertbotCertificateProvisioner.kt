package com.gatekeeper.feature.nginx.infrastructure

import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.model.ResolvedCertificate
import com.gatekeeper.plugins.DistributedLock
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.time.Instant
import java.time.ZoneOffset

class CertbotCertificateProvisioner(
    private val processRunner: ProcessRunner,
    private val sslCertPath: String = "/etc/letsencrypt/live",
    private val nginxSiteStorage: NginxSiteStorage,
    private val selfDomainExclusion: NginxSelfDomainExclusion,
    private val validator: NginxValidationService
) : CertificateProvisioner {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.admin.NginxAdminRoutes")


    override fun isCertbotAvailable(): Boolean {
        return try {
            processRunner.run(listOf("which", "certbot")).exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun certificateManagerIssue(): String? {
        if (!isCertbotAvailable()) return "Certbot is not installed or is not available in the Gatekeeperd service PATH."
        val helper = File("/usr/local/sbin/gatekeeperd-certbot")
        if (!helper.isFile || !helper.canExecute()) {
            return "The restricted Gatekeeperd Certbot helper is missing. Redeploy with the current deploy.sh to install it."
        }
        return try {
            val result = processRunner.runStreaming(
                listOf("sudo", "-n", "-l", helper.absolutePath),
                timeoutMillis = 5_000
            )
            if (result.exitCode != 0) {
                "Timed out checking Gatekeeperd's Certbot permissions."
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Could not verify Certbot helper permissions", e)
            "Could not verify Gatekeeperd's Certbot permissions. Check the service sudoers configuration."
        }
    }

    fun runCertbotHelper(vararg arguments: String): Int? {
        return try {
            processRunner.runStreaming(
                listOf("sudo", "-n", "/usr/local/sbin/gatekeeperd-certbot") + arguments,
                timeoutMillis = java.util.concurrent.TimeUnit.MINUTES.toMillis(8),
                onOutput = { line -> logger.info(line) }
            ).exitCode
        } catch (e: com.gatekeeper.core.ports.ProcessTimeoutException) {
            logger.error("Certbot helper timed out after 8 minutes: ${arguments.firstOrNull() ?: "unknown operation"}")
            null
        }
    }

    override fun installCertificate(domain: String, email: String): Boolean {
        validator.requireValidHostname(domain)
        validator.requireValidEmail(email)
        certificateManagerIssue()?.let { issue ->
            logger.error("Certificate manager unavailable: $issue")
            return false
        }

        return DistributedLock.withLock("nginx-host-mutation") {
            try {
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
            }
        }
    }

    override fun renewCertificate(domain: String): Boolean {
        validator.requireValidHostname(domain)
        return !(!isCertificateInstalled(domain) || certificateManagerIssue() != null) && DistributedLock.withLock("nginx-host-mutation") {
            try {
                val exitCode = runCertbotHelper("renew", domain)
                if (exitCode != 0) {
                    logger.error("Certbot renewal failed for domain $domain (exit=${exitCode ?: "timeout"})")
                    return@withLock false
                }
                if (!nginxSiteStorage.reloadNginx()) {
                    logger.error("Nginx reload failed after Certbot renewal for domain $domain")
                    return@withLock false
                }
                logger.info("Certbot renewal check completed for domain: $domain")
                true
            } catch (e: Exception) {
                logger.error("Failed to renew certificate for $domain", e)
                false
            }
        }
    }

    override fun removeCertificate(domain: String): Boolean {
        validator.requireValidHostname(domain)
        if (selfDomainExclusion.excludesDomain(domain)) {
            logger.warn("Refusing to remove certificate $domain because it covers a Gatekeeperd self domain")
            return false
        }
        certificateManagerIssue()?.let { issue ->
            logger.error("Cannot remove certificate for $domain: $issue")
            return false
        }

        return DistributedLock.withLock("nginx-host-mutation") {
            try {
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
            }
        }
    }

    override fun isCertificateInstalled(domain: String): Boolean {
        validator.requireValidHostname(domain)
        val certDir = File("$sslCertPath/$domain")
        return certDir.exists() &&
                File(certDir, "fullchain.pem").exists() &&
                File(certDir, "privkey.pem").exists()
    }

    override fun resolveCertificateForDomain(domain: String, requestedCertificateDomain: String?): ResolvedCertificate? {
        validator.requireValidHostname(domain)
        val requested = requestedCertificateDomain?.trim()?.takeIf { it.isNotBlank() }
        requested?.let { validator.requireValidHostname(it, "certificateDomain") }
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

    override fun listInstalledCertificates(): List<ResolvedCertificate> {
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

    override fun resolveInstalledCertificateByName(certificateDomain: String): ResolvedCertificate? {
        if (!isCertificateInstalled(certificateDomain)) return null
        return ResolvedCertificate(
            certificateDomain = certificateDomain,
            certificatePath = "$sslCertPath/$certificateDomain/fullchain.pem",
            privateKeyPath = "$sslCertPath/$certificateDomain/privkey.pem"
        )
    }

    override fun parentDomainCandidates(domain: String): List<String> {
        val parts = domain.trim().trimEnd('.').split(".").filter { it.isNotBlank() }
        if (parts.size <= 2) return emptyList()

        val candidates = mutableListOf<String>()
        for (start in 1..(parts.size - 2)) {
            candidates.add(parts.subList(start, parts.size).joinToString("."))
        }
        return candidates
    }

    override fun certificateExpiry(domain: String): Pair<String, Long>? {
        return try {
            validator.requireValidHostname(domain)
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
}
