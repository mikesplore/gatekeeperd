package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.RegistryCredentials
import com.gatekeeper.db.tables.ProviderCredentials
import com.gatekeeper.security.SecretValueCipher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.UUID

data class RegistryCredentialRecord(val registry: String, val username: String, val password: String, val updatedAt: LocalDateTime)

object RegistryCredentialRepository {
    private const val PROVIDER = "docker"
    private const val TYPE = "registry"

    @Serializable
    private data class RegistryPayload(val username: String, val password: String)

    /** Metadata only: never decrypts or returns registry passwords. */
    fun list(): List<Pair<String, String>> = transaction {
        val current = ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq PROVIDER) and (ProviderCredentials.credentialType eq TYPE)
        }.associate { it[ProviderCredentials.scope] to it[ProviderCredentials.displayName] }
        val legacy = RegistryCredentials.selectAll().map { it[RegistryCredentials.registry] to it[RegistryCredentials.username] }
        (legacy.toMap() + current).toSortedMap().map { it.key to it.value }
    }

    fun find(registry: String): RegistryCredentialRecord? = transaction {
        val providerRow = ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq PROVIDER) and
                (ProviderCredentials.credentialType eq TYPE) and
                (ProviderCredentials.scope eq registry)
        }.singleOrNull()
        if (providerRow != null) {
            val payload = Json.decodeFromString<RegistryPayload>(SecretValueCipher.decrypt(providerRow[ProviderCredentials.encryptedPayload]))
            return@transaction RegistryCredentialRecord(registry, payload.username, payload.password, providerRow[ProviderCredentials.updatedAt])
        }
        RegistryCredentials.selectAll().where { RegistryCredentials.registry eq registry }.singleOrNull()?.let {
            RegistryCredentialRecord(it[RegistryCredentials.registry], it[RegistryCredentials.username], SecretValueCipher.decrypt(it[RegistryCredentials.passwordEncrypted]), it[RegistryCredentials.updatedAt])
        }
    }

    fun save(registry: String, username: String, password: String) = transaction {
        val now = LocalDateTime.now()
        val payload = SecretValueCipher.encrypt(Json.encodeToString(RegistryPayload(username, password)))
        val existing = ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq PROVIDER) and
                (ProviderCredentials.credentialType eq TYPE) and
                (ProviderCredentials.scope eq registry)
        }.singleOrNull()
        if (existing == null) {
            ProviderCredentials.insert {
                it[id] = UUID.randomUUID()
                it[ProviderCredentials.provider] = PROVIDER
                it[ProviderCredentials.credentialType] = TYPE
                it[ProviderCredentials.displayName] = username
                it[ProviderCredentials.scope] = registry
                it[encryptedPayload] = payload
                it[rotatedAt] = now
                it[rotatedBy] = "admin"
                it[createdAt] = now
                it[createdBy] = "admin"
                it[updatedAt] = now
                it[updatedBy] = "admin"
            }
        } else {
            ProviderCredentials.update({ ProviderCredentials.id eq existing[ProviderCredentials.id] }) {
                it[ProviderCredentials.displayName] = username
                it[encryptedPayload] = payload
                it[rotatedAt] = now
                it[rotatedBy] = "admin"
                it[updatedAt] = now
                it[updatedBy] = "admin"
            }
        }

        // Compatibility copy for older workers/instances during the rollout.
        val encrypted = SecretValueCipher.encrypt(password)
        RegistryCredentials.upsert(RegistryCredentials.registry) {
            it[RegistryCredentials.registry] = registry; it[RegistryCredentials.username] = username; it[passwordEncrypted] = encrypted; it[updatedAt] = now
        }
    }

    fun delete(registry: String): Boolean = transaction {
        val removedProvider = ProviderCredentials.deleteWhere {
            (ProviderCredentials.provider eq PROVIDER) and
                (ProviderCredentials.credentialType eq TYPE) and
                (ProviderCredentials.scope eq registry)
        } > 0
        val removedLegacy = RegistryCredentials.deleteWhere { RegistryCredentials.registry eq registry } > 0
        removedProvider || removedLegacy
    }
}
