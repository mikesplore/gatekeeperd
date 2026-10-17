package com.gatekeeper.feature.certificate.domain.repository

import java.time.LocalDateTime
import java.util.UUID

interface CertificateRepository {
    data class Record(val id: UUID, val domain: String, val issuedAt: LocalDateTime?, val expiresAt: LocalDateTime?, val renewalStatus: String, val lastRenewalAttempt: LocalDateTime?, val lastRenewalError: String?)
    fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, renewalStatus: String, error: String? = null): Record
    fun findByDomain(domain: String): Record?
    fun findAll(): List<Record>
    fun syncInventory(domain: String, expiresAt: LocalDateTime?, renewalStatus: String): Record
    fun recordRenewalResult(domain: String, expiresAt: LocalDateTime?, renewalStatus: String, error: String? = null): Record
    fun activeSiteCount(domain: String): Long
    fun requireRemovable(domain: String, linkedActiveSites: Long = activeSiteCount(domain))
    fun deleteByDomain(domain: String)
}
