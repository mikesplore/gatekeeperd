package com.gatekeeper.db.repositories

import com.gatekeeper.feature.integrations.data.persistence.ExposedProviderCredentialRepository
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import java.util.UUID

typealias ProviderCredentialMetadata = com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata
typealias StoredProviderCredential = com.gatekeeper.feature.integrations.domain.repository.StoredProviderCredential

/** @deprecated Inject ProviderCredentialStore instead. */
@Deprecated("Inject ProviderCredentialStore")
object ProviderCredentialRepository {
    private val delegate: ProviderCredentialStore = ExposedProviderCredentialRepository()

    fun listMetadata(provider: String? = null, credentialType: String? = null) = delegate.listMetadata(provider, credentialType)
    fun findMetadata(id: UUID) = delegate.findMetadata(id)
    fun findById(id: UUID) = delegate.findById(id)
    fun findCurrent(provider: String, credentialType: String, scope: String) = delegate.findCurrent(provider, credentialType, scope)
    fun retireCurrent(provider: String, credentialType: String, scope: String, actor: String = "admin") =
        delegate.retireCurrent(provider, credentialType, scope, actor)
    fun createOrRotate(provider: String, credentialType: String, displayName: String, scope: String, payload: String, actor: String = "admin") =
        delegate.createOrRotate(provider, credentialType, displayName, scope, payload, actor)
}
