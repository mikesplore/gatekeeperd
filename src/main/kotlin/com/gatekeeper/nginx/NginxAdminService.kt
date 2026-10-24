package com.gatekeeper.nginx

import com.gatekeeper.plugins.DistributedLock
import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository
import com.gatekeeper.feature.nginx.application.inspectSite
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.requireValidEmail
import com.gatekeeper.feature.nginx.domain.service.requireValidHostname
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.validateCustomLocations
import com.gatekeeper.feature.nginx.presentation.CertificateListResponse
import com.gatekeeper.feature.nginx.presentation.CertificateRenewalResponse
import com.gatekeeper.feature.nginx.presentation.CertificateResponse
import com.gatekeeper.feature.nginx.presentation.InstalledCertificateInfo
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocation
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocationResponse
import com.gatekeeper.feature.nginx.presentation.NginxEnableRequest
import com.gatekeeper.feature.service.domain.repository.SiteDashboardUpdate
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Application-level operations for nginx administration routes. */
class NginxAdminService(
    private val certificates: CertificateStore,
    private val sslCertPath: String,
    private val siteRepository: SiteRepository,
    private val certificateProvisioner: CertificateProvisioner,
    private val nginxSiteStorage: NginxSiteStorage,
) {
    fun updateCustomLocations(
        slug: String,
        site: SiteRecord,
        locations: List<NginxCustomLocation>,
        certificate: CertificateProvisioner,
        renderModel: (SiteRecord) -> NginxSiteRenderModel
    ): NginxCustomLocationResponse = DistributedLock.withLock("nginx-host-mutation") {
        validateCustomLocations(locations)
        val inspection = inspectSite(slug, siteRepository = siteRepository)
        require(inspection.available && inspection.enabled && inspection.managed) {
            "Custom locations can only be managed for an enabled Gatekeeperd-managed Nginx site"
        }
        val previous = site.customNginxLocations
        val updated = siteRepository.updateDashboard(
            site.id,
            SiteDashboardUpdate(customNginxLocations = locations)
        ) ?: error("Site no longer exists")
        val activation = runCatching {
            nginxSiteStorage.enableProjectDetailed(slug, nginx.generateNginxConfig(renderModel(updated)))
        }.getOrElse { error ->
            siteRepository.updateDashboard(site.id, SiteDashboardUpdate(customNginxLocations = previous))
            throw error
        }
        if (!activation.success) {
            siteRepository.updateDashboard(site.id, SiteDashboardUpdate(customNginxLocations = previous))
            error(activation.message ?: "Nginx rejected the custom location update")
        }
        NginxCustomLocationResponse(true, "Custom Nginx locations saved and reloaded", locations)
    }

    fun hasRenderAffectingParameters(body: NginxEnableRequest): Boolean =
        body.port != null || body.domain != null || body.upstreamScheme != null ||
            body.certificateDomain != null || body.sslCertificatePath != null ||
            body.sslCertificateKeyPath != null || body.requireSsl != null

    fun listCertificates(): CertificateListResponse = CertificateListResponse(
        certificateProvisioner.listInstalledCertificates().map { cert ->
            val expiry = certificateProvisioner.certificateExpiry(cert.certificateDomain)
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
        val email = try {
            requireValidEmail(emailInput.trim())
        } catch (e: IllegalArgumentException) {
            return CertificateOperationResult.InvalidEmail(e.message ?: "Invalid email")
        }
        if (!certificateProvisioner.isCertbotAvailable()) return CertificateOperationResult.CertbotUnavailable
        if (!certificateProvisioner.installCertificate(domain, email)) return CertificateOperationResult.Failed
        val expiry = certificateProvisioner.certificateExpiry(domain)
        val record = certificates.upsert(
            domain,
            LocalDateTime.now(),
            expiry?.first?.let(OffsetDateTime::parse)?.toLocalDateTime(),
            if (expiry == null) "unknown" else "active"
        )
        certificates.linkSites(domain, record.id)
        return CertificateOperationResult.Success(
            CertificateResponse(domain, certificateProvisioner.isCertificateInstalled(domain), "$sslCertPath/$domain/fullchain.pem", "$sslCertPath/$domain/privkey.pem")
        )
    }

    fun removeCertificate(domainInput: String): CertificateOperationResult<CertificateResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        try { certificates.requireRemovable(domain) } catch (e: IllegalArgumentException) {
            return CertificateOperationResult.InUse(e.message ?: "Certificate is still referenced by active sites")
        }
        if (!certificateProvisioner.removeCertificate(domain)) return CertificateOperationResult.Failed
        certificates.delete(domain)
        return CertificateOperationResult.Success(CertificateResponse(domain, false))
    }

    fun certificateStatus(domainInput: String): CertificateOperationResult<CertificateResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        val installed = certificateProvisioner.isCertificateInstalled(domain)
        return CertificateOperationResult.Success(
            CertificateResponse(
                domain,
                installed,
                if (installed) "$sslCertPath/$domain/fullchain.pem" else null,
                if (installed) "$sslCertPath/$domain/privkey.pem" else null
            )
        )
    }

    fun renewCertificate(domainInput: String): CertificateOperationResult<CertificateRenewalResponse> {
        val domain = validateDomain(domainInput) ?: return CertificateOperationResult.InvalidDomain
        if (!certificateProvisioner.isCertificateInstalled(domain)) return CertificateOperationResult.NotFound
        if (!certificateProvisioner.isCertbotAvailable()) return CertificateOperationResult.CertbotUnavailable
        val before = certificateProvisioner.certificateExpiry(domain)
        if (!certificateProvisioner.renewCertificate(domain)) {
            val status = when { before == null -> "unknown"; before.second < 0 -> "expired"; else -> "active" }
            certificates.recordRenewal(domain, before?.first?.let(OffsetDateTime::parse)?.toLocalDateTime(), status, "Certbot renewal failed")
            return CertificateOperationResult.RenewalFailed
        }
        val after = certificateProvisioner.certificateExpiry(domain)
        val parsed = after?.first?.let(OffsetDateTime::parse)
        val status = when { parsed == null -> "unknown"; parsed.toInstant().isBefore(Instant.now()) -> "expired"; else -> "active" }
        certificates.recordRenewal(domain, parsed?.toLocalDateTime(), status, null)
        val renewed = before?.first != after?.first
        return CertificateOperationResult.Success(
            CertificateRenewalResponse(domain, renewed, after?.first, after?.second, if (renewed) "Certificate renewed successfully" else "Certificate is not due for renewal yet")
        )
    }

    private fun validateDomain(value: String): String? = try {
        requireValidHostname(value.trim())
    } catch (_: IllegalArgumentException) { null }
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

class ExposedCertificateStore(private val repository: CertificateRepository, private val siteRepository: SiteRepository) : CertificateStore {
    override fun syncInventory(domain: String, expiresAt: LocalDateTime?, status: String) { repository.syncInventory(domain, expiresAt, status) }
    override fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, status: String) = CertificateStore.Record(repository.upsert(domain, issuedAt, expiresAt, status).id)
    override fun linkSites(domain: String, certificateId: java.util.UUID) { siteRepository.linkCertificateForDomain(domain, certificateId) }
    override fun requireRemovable(domain: String) = repository.requireRemovable(domain)
    override fun delete(domain: String) { repository.deleteByDomain(domain) }
    override fun recordRenewal(domain: String, expiresAt: LocalDateTime?, status: String, error: String?) { repository.recordRenewalResult(domain, expiresAt, status, error) }
}
