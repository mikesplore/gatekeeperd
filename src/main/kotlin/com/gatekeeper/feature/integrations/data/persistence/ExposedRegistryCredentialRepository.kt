package com.gatekeeper.feature.integrations.data.persistence

import com.gatekeeper.db.tables.RegistryCredentials
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialStore
import com.gatekeeper.security.SecretValueCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.time.LocalDateTime
import java.util.UUID

class ExposedRegistryCredentialRepository(private val credentials: ProviderCredentialStore) : RegistryCredentialStore {
    @Serializable
    private data class RegistryPayload(val username: String, val password: String)

    override fun list(): List<Pair<String, String>> = transaction {
        val current = credentials.listMetadata(PROVIDER, TYPE)
            .filter { it.current }.associate { it.scope to it.displayName }
        val legacy = RegistryCredentials.selectAll().map { it[RegistryCredentials.registry] to it[RegistryCredentials.username] }
        (legacy.toMap() + current).toSortedMap().map { it.key to it.value }
    }

    override fun find(registry: String): RegistryCredentialRecord? = credentials.findCurrent(PROVIDER, TYPE, registry)?.let {
        it.toRecord(registry)
    } ?: transaction {
        RegistryCredentials.selectAll().where { RegistryCredentials.registry eq registry }.singleOrNull()?.let {
            RegistryCredentialRecord(
                it[RegistryCredentials.registry], it[RegistryCredentials.username],
                SecretValueCipher.decrypt(it[RegistryCredentials.passwordEncrypted]), it[RegistryCredentials.updatedAt]
            )
        }
    }

    override fun findByCredentialId(id: UUID): RegistryCredentialRecord? = credentials.findById(id)?.let { stored ->
        if (stored.metadata.provider != PROVIDER || stored.metadata.credentialType != TYPE) return@let null
        stored.toRecord(stored.metadata.scope)
    }

    override fun save(registry: String, username: String, password: String, displayName: String?): ProviderCredentialMetadata = transaction {
        val now = LocalDateTime.now()
        val credential = credentials.createOrRotate(
            PROVIDER, TYPE, displayName ?: username, registry, Json.encodeToString(RegistryPayload(username, password))
        )
        val encrypted = SecretValueCipher.encrypt(password)
        RegistryCredentials.upsert(RegistryCredentials.registry) {
            it[RegistryCredentials.registry] = registry
            it[RegistryCredentials.username] = username
            it[passwordEncrypted] = encrypted
            it[updatedAt] = now
        }
        credential
    }

    override fun delete(registry: String): Boolean = transaction {
        val removedProvider = credentials.retireCurrent(PROVIDER, TYPE, registry)
        val removedLegacy = RegistryCredentials.deleteWhere { RegistryCredentials.registry eq registry } > 0
        removedProvider || removedLegacy
    }

    private fun com.gatekeeper.feature.integrations.domain.repository.StoredProviderCredential.toRecord(registry: String): RegistryCredentialRecord {
        val value = Json.decodeFromString<RegistryPayload>(payload)
        return RegistryCredentialRecord(registry, value.username, value.password, metadata.updatedAt, metadata.id, metadata.version)
    }

    private companion object {
        const val PROVIDER = "docker"
        const val TYPE = "registry"
    }
}
