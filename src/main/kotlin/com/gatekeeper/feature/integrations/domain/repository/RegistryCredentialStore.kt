package com.gatekeeper.feature.integrations.domain.repository

import java.time.LocalDateTime
import java.util.UUID

data class RegistryCredentialRecord(
    val registry: String,
    val username: String,
    val password: String,
    val updatedAt: LocalDateTime,
    val credentialId: UUID? = null,
    val version: Int? = null
)

interface RegistryCredentialStore {
    fun list(): List<Pair<String, String>>
    fun find(registry: String): RegistryCredentialRecord?
    fun findByCredentialId(id: UUID): RegistryCredentialRecord?
    fun save(registry: String, username: String, password: String, displayName: String? = null): ProviderCredentialMetadata
    fun delete(registry: String): Boolean
}
