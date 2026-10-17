package com.gatekeeper.feature.integrations.data.persistence

import com.gatekeeper.db.repositories.ProviderCredentialRepository
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.StoredProviderCredential
import java.util.UUID

/** Compatibility adapter while credential persistence callers move off the repository singleton. */
class LegacyProviderCredentialStoreAdapter : ProviderCredentialStore {
    override fun listMetadata(provider: String?, credentialType: String?) = ProviderCredentialRepository.listMetadata(provider, credentialType)
    override fun findMetadata(id: UUID) = ProviderCredentialRepository.findMetadata(id)
    override fun findById(id: UUID): StoredProviderCredential? = ProviderCredentialRepository.findById(id)
    override fun findCurrent(provider: String, credentialType: String, scope: String): StoredProviderCredential? =
        ProviderCredentialRepository.findCurrent(provider, credentialType, scope)
    override fun retireCurrent(provider: String, credentialType: String, scope: String, actor: String) =
        ProviderCredentialRepository.retireCurrent(provider, credentialType, scope, actor)
    override fun createOrRotate(provider: String, credentialType: String, displayName: String, scope: String, payload: String, actor: String): ProviderCredentialMetadata =
        ProviderCredentialRepository.createOrRotate(provider, credentialType, displayName, scope, payload, actor)
}
