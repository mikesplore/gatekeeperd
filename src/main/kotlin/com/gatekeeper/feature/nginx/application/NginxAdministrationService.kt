package com.gatekeeper.feature.nginx.application

import com.gatekeeper.plugins.DistributedLock
import com.gatekeeper.feature.nginx.domain.port.NginxCertificateStore
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.infrastructure.NginxConfigRenderer as InfrastructureNginxConfigRenderer
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.presentation.CertificateListResponse
import com.gatekeeper.feature.nginx.presentation.CertificateOperationResult
import com.gatekeeper.feature.nginx.presentation.CertificateRenewalResponse
import com.gatekeeper.feature.nginx.presentation.CertificateResponse
import com.gatekeeper.feature.nginx.presentation.InstalledCertificateInfo
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocation
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocationResponse
import com.gatekeeper.feature.service.domain.repository.SiteDashboardUpdate
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Application-level operations for nginx administration routes. */
class NginxAdministrationService(
    private val certificates: NginxCertificateStore,
    private val sslCertPath: String,
    private val siteRepository: SiteRepository,
    private val certificateProvisioner: CertificateProvisioner,
    private val domainRules: DomainRules,
    private val applySiteConfig: ApplySiteConfigUseCase,
    private val inspectSiteConfig: InspectSiteConfigUseCase,
    private val nginxConfigRenderer: InfrastructureNginxConfigRenderer,
    private val nValidation: NginxValidationService
) {
    fun updateCustomLocations(
        slug: String,
        site: SiteRecord,
        locations: List<NginxCustomLocation>,
        renderModel: (SiteRecord) -> NginxSiteRenderModel
    ): NginxCustomLocationResponse = DistributedLock.withLock("nginx-host-mutation") {
        domainRules.validateCustomLocations(locations)
        val inspection = inspectSiteConfig.inspectSite(slug)
        require(inspection.available && inspection.enabled && inspection.managed) {
            "Custom locations can only be managed for an enabled Gatekeeperd-managed Nginx site"
        }
        val previous = site.customNginxLocations
        val updated = siteRepository.updateDashboard(
            site.id,
            SiteDashboardUpdate(customNginxLocations = locations)
        ) ?: error("Site no longer exists")
        val activation = runCatching {
            applySiteConfig.enableProjectDetailed(slug, nginxConfigRenderer.render(renderModel(updated)))
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
            nValidation.requireValidEmail(emailInput.trim())
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
        nValidation.requireValidHostname(value.trim())
    } catch (_: IllegalArgumentException) { null }
}

