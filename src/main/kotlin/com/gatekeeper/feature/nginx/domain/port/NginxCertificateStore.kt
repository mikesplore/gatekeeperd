package com.gatekeeper.feature.nginx.domain.port

import java.time.LocalDateTime
import java.util.UUID

interface NginxCertificateStore {
    data class Record(val id: UUID)

    fun syncInventory(domain: String, expiresAt: LocalDateTime?, status: String)
    fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, status: String): Record
    fun linkSites(domain: String, certificateId: UUID)
    fun requireRemovable(domain: String)
    fun delete(domain: String)
    fun recordRenewal(domain: String, expiresAt: LocalDateTime?, status: String, error: String?)
}
