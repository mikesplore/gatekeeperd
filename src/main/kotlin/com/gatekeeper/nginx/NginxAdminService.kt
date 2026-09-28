package com.gatekeeper.nginx

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.CertificateRepository
import com.gatekeeper.db.repositories.SiteRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Application-level operations for nginx administration routes. */
class NginxAdminService(
    private val nginx: NginxService,
    private val certificates: CertificateStore = ExposedCertificateStore
) {
    fun hasRenderAffectingParameters(body: NginxEnableRequest): Boolean =
        body.port != null || body.domain != null || body.upstreamScheme != null ||
            body.certificateDomain != null || body.sslCertificatePath != null ||
            body.sslCertificateKeyPath != null || body.requireSsl != null

    fun listCertificates(): CertificateListResponse = CertificateListResponse(
        nginx.listInstalledCertificates().map { cert ->
            val expiry = nginx.certificateExpiry(cert.certificateDomain)
            val status = when {
                expiry == null -> "unknown"
                OffsetDateTime.parse(expiry.first).toInstant().isBefore(Instant.now()) -> "expired"
                else -> "active"
            }
            certificates.syncInventory(cert.certificateDomain, expiry?.first?.let(OffsetDateTime::parse)?.toLocalDateTime(), status)
            InstalledCertificateInfo(
                certificateDomain = cert.certificateDomain,
                certificatePath = cert.certificatePath,
                privateKeyPath = cert.privateKeyPath,
                certificateExpiresAt = expiry?.first,
                certificateDaysRemaining = expiry?.second,
                renewalStatus = status
            )
        }
    )

    fun installCertificate(domainInput: String, emailInput: String): CertificateOperationResult<CertificateResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        val email = try { requireValidEmail(emailInput.trim()) } catch (e: IllegalArgumentException) {
            return CertificateOperationResult.InvalidEmail(e.message ?: "Invalid email")
        }
        if (!nginx.isCertbotAvailable()) return CertificateOperationResult.CertbotUnavailable
        if (!nginx.installCertificate(domain, email)) return CertificateOperationResult.Failed
        val expiry = nginx.certificateExpiry(domain)
        val record = certificates.upsert(
            domain,
            LocalDateTime.now(),
            expiry?.first?.let(OffsetDateTime::parse)?.toLocalDateTime(),
            if (expiry == null) "unknown" else "active"
        )
        certificates.linkSites(domain, record.id)
        return CertificateOperationResult.Success(
            CertificateResponse(domain, nginx.isCertificateInstalled(domain), "${AppConfig.nginxSslCertPath}/$domain/fullchain.pem", "${AppConfig.nginxSslCertPath}/$domain/privkey.pem")
        )
    }

    fun removeCertificate(domainInput: String): CertificateOperationResult<CertificateResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        try { certificates.requireRemovable(domain) } catch (e: IllegalArgumentException) {
            return CertificateOperationResult.InUse(e.message ?: "Certificate is still referenced by active sites")
        }
        if (!nginx.removeCertificate(domain)) return CertificateOperationResult.Failed
        certificates.delete(domain)
        return CertificateOperationResult.Success(CertificateResponse(domain, false))
    }

    fun certificateStatus(domainInput: String): CertificateOperationResult<CertificateResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        val installed = nginx.isCertificateInstalled(domain)
        return CertificateOperationResult.Success(
            CertificateResponse(
                domain,
                installed,
                if (installed) "${AppConfig.nginxSslCertPath}/$domain/fullchain.pem" else null,
                if (installed) "${AppConfig.nginxSslCertPath}/$domain/privkey.pem" else null
            )
        )
    }

    fun renewCertificate(domainInput: String): CertificateOperationResult<CertificateRenewalResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        if (!nginx.isCertificateInstalled(domain)) return CertificateOperationResult.NotFound
        if (!nginx.isCertbotAvailable()) return CertificateOperationResult.CertbotUnavailable
        val before = nginx.certificateExpiry(domain)
        if (!nginx.renewCertificate(domain)) {
            val status = when { before == null -> "unknown"; before.second < 0 -> "expired"; else -> "active" }
            certificates.recordRenewal(domain, before?.first?.let(OffsetDateTime::parse)?.toLocalDateTime(), status, "Certbot renewal failed")
            return CertificateOperationResult.RenewalFailed
        }
        val after = nginx.certificateExpiry(domain)
        val parsed = after?.first?.let(OffsetDateTime::parse)
        val status = when { parsed == null -> "unknown"; parsed.toInstant().isBefore(Instant.now()) -> "expired"; else -> "active" }
        certificates.recordRenewal(domain, parsed?.toLocalDateTime(), status, null)
        val renewed = before?.first != after?.first
        return CertificateOperationResult.Success(
            CertificateRenewalResponse(domain, renewed, after?.first, after?.second, if (renewed) "Certificate renewed successfully" else "Certificate is not due for renewal yet")
        )
    }

    private fun validateDomain(value: String): String? = try { requireValidHostname(value.trim()) } catch (_: IllegalArgumentException) { null }
}

sealed interface CertificateOperationResult<out T> {
    data class Success<T>(val value: T) : CertificateOperationResult<T>
    data object InvalidDomain : CertificateOperationResult<Nothing>
    data class InvalidEmail(val message: String) : CertificateOperationResult<Nothing>
    data object CertbotUnavailable : CertificateOperationResult<Nothing>
    data object Failed : CertificateOperationResult<Nothing>
    data object NotFound : CertificateOperationResult<Nothing>
    data object RenewalFailed : CertificateOperationResult<Nothing>
    data class InUse(val message: String) : CertificateOperationResult<Nothing>
}

interface CertificateStore {
    data class Record(val id: java.util.UUID)
    fun syncInventory(domain: String, expiresAt: LocalDateTime?, status: String)
    fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, status: String): Record
    fun linkSites(domain: String, certificateId: java.util.UUID)
    fun requireRemovable(domain: String)
    fun delete(domain: String)
    fun recordRenewal(domain: String, expiresAt: LocalDateTime?, status: String, error: String?)
}

object ExposedCertificateStore : CertificateStore {
    override fun syncInventory(domain: String, expiresAt: LocalDateTime?, status: String) { CertificateRepository.syncInventory(domain, expiresAt, status) }
    override fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, status: String) = CertificateStore.Record(CertificateRepository.upsert(domain, issuedAt, expiresAt, status).id)
    override fun linkSites(domain: String, certificateId: java.util.UUID) { SiteRepository.linkCertificateForDomain(domain, certificateId) }
    override fun requireRemovable(domain: String) = CertificateRepository.requireRemovable(domain)
    override fun delete(domain: String) { CertificateRepository.deleteByDomain(domain) }
    override fun recordRenewal(domain: String, expiresAt: LocalDateTime?, status: String, error: String?) { CertificateRepository.recordRenewalResult(domain, expiresAt, status, error) }
}
