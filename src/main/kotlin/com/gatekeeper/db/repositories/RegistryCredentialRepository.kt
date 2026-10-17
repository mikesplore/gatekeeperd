package com.gatekeeper.db.repositories

import com.gatekeeper.feature.integrations.data.persistence.ExposedProviderCredentialRepository
import com.gatekeeper.feature.integrations.data.persistence.ExposedRegistryCredentialRepository
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialStore
import java.util.UUID

typealias RegistryCredentialRecord = com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord

/** @deprecated Inject RegistryCredentialStore instead. */
@Deprecated("Inject RegistryCredentialStore")
object RegistryCredentialRepository {
    private val delegate: RegistryCredentialStore = ExposedRegistryCredentialRepository(ExposedProviderCredentialRepository())

    fun list() = delegate.list()
    fun find(registry: String) = delegate.find(registry)
    fun findByCredentialId(id: UUID) = delegate.findByCredentialId(id)
    fun save(registry: String, username: String, password: String, displayName: String? = null): ProviderCredentialMetadata =
        delegate.save(registry, username, password, displayName)
    fun delete(registry: String) = delegate.delete(registry)
}
