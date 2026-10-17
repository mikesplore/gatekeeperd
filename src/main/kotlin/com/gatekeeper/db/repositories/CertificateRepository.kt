package com.gatekeeper.db.repositories

import com.gatekeeper.feature.certificate.data.persistence.ExposedCertificateRepository

/** @deprecated Inject feature.certificate.domain.repository.CertificateRepository instead. */
@Deprecated("Inject CertificateRepository")
object CertificateRepository {
    typealias CertificateRecord = com.gatekeeper.feature.certificate.domain.repository.CertificateRepository.Record
    private val delegate = ExposedCertificateRepository()
    fun upsert(domain: String, issuedAt: java.time.LocalDateTime?, expiresAt: java.time.LocalDateTime?, renewalStatus: String, error: String? = null) = delegate.upsert(domain, issuedAt, expiresAt, renewalStatus, error)
    fun findByDomain(domain: String) = delegate.findByDomain(domain)
    fun findAll() = delegate.findAll()
    fun syncInventory(domain: String, expiresAt: java.time.LocalDateTime?, renewalStatus: String) = delegate.syncInventory(domain, expiresAt, renewalStatus)
    fun recordRenewalResult(domain: String, expiresAt: java.time.LocalDateTime?, renewalStatus: String, error: String? = null) = delegate.recordRenewalResult(domain, expiresAt, renewalStatus, error)
    fun activeSiteCount(domain: String) = delegate.activeSiteCount(domain)
    fun requireRemovable(domain: String, linkedActiveSites: Long = activeSiteCount(domain)) = delegate.requireRemovable(domain, linkedActiveSites)
    fun deleteByDomain(domain: String) = delegate.deleteByDomain(domain)
}
