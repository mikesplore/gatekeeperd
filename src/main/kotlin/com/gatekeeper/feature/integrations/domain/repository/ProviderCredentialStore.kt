package com.gatekeeper.feature.integrations.domain.repository

import java.time.LocalDateTime
import java.util.UUID

data class ProviderCredentialMetadata(
    val id: UUID,
    val provider: String,
    val credentialType: String,
    val displayName: String,
    val scope: String,
    val version: Int,
    val rotatedAt: LocalDateTime?,
    val rotatedBy: String?,
    val supersededAt: LocalDateTime?,
    val supersededById: UUID?,
    val createdAt: LocalDateTime,
    val createdBy: String?,
    val updatedAt: LocalDateTime,
    val updatedBy: String?
) {
    val current: Boolean get() = supersededAt == null
}

data class StoredProviderCredential(val metadata: ProviderCredentialMetadata, val payload: String)

interface ProviderCredentialStore {
    fun listMetadata(provider: String? = null, credentialType: String? = null): List<ProviderCredentialMetadata>
    fun findMetadata(id: UUID): ProviderCredentialMetadata?
    fun findById(id: UUID): StoredProviderCredential?
    fun findCurrent(provider: String, credentialType: String, scope: String): StoredProviderCredential?
    fun retireCurrent(provider: String, credentialType: String, scope: String, actor: String = "admin"): Boolean
    fun createOrRotate(provider: String, credentialType: String, displayName: String, scope: String, payload: String, actor: String = "admin"): ProviderCredentialMetadata
}
