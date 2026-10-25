package com.gatekeeper.feature.nginx.infrastructure

import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository
import com.gatekeeper.feature.nginx.domain.port.NginxCertificateStore
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import java.time.LocalDateTime
import java.util.UUID

class ExposedNginxCertificateStore(
    private val certificateRepository: CertificateRepository,
    private val siteRepository: SiteRepository
) : NginxCertificateStore {
    override fun syncInventory(domain: String, expiresAt: LocalDateTime?, status: String) {
        certificateRepository.syncInventory(domain, expiresAt, status)
    }

    override fun upsert(
        domain: String,
        issuedAt: LocalDateTime?,
        expiresAt: LocalDateTime?,
        status: String
    ): NginxCertificateStore.Record =
        NginxCertificateStore.Record(
            certificateRepository.upsert(domain, issuedAt, expiresAt, status).id
        )

    override fun linkSites(domain: String, certificateId: UUID) {
        siteRepository.linkCertificateForDomain(domain, certificateId)
    }

    override fun requireRemovable(domain: String) {
        certificateRepository.requireRemovable(domain)
    }

    override fun delete(domain: String) {
        certificateRepository.deleteByDomain(domain)
    }

    override fun recordRenewal(domain: String, expiresAt: LocalDateTime?, status: String, error: String?) {
        certificateRepository.recordRenewalResult(domain, expiresAt, status, error)
    }
}
