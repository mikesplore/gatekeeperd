package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.RegistryCredentials
import com.gatekeeper.security.SecretValueCipher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

object RegistryCredentialRepository {
    private const val PROVIDER = "docker"
    private const val TYPE = "registry"

    @Serializable
    private data class RegistryPayload(val username: String, val password: String)

    /** Metadata only: never decrypts or returns registry passwords. */
    fun list(): List<Pair<String, String>> = transaction {
        val current = ProviderCredentialRepository.listMetadata(PROVIDER, TYPE)
            .filter { it.current }.associate { it.scope to it.displayName }
        val legacy = RegistryCredentials.selectAll().map { it[RegistryCredentials.registry] to it[RegistryCredentials.username] }
        (legacy.toMap() + current).toSortedMap().map { it.key to it.value }
    }

    fun find(registry: String): RegistryCredentialRecord? = ProviderCredentialRepository.findCurrent(PROVIDER, TYPE, registry)?.let { current ->
        val payload = Json.decodeFromString<RegistryPayload>(current.payload)
        RegistryCredentialRecord(registry, payload.username, payload.password, current.metadata.updatedAt, current.metadata.id, current.metadata.version)
    } ?: transaction {
        RegistryCredentials.selectAll().where { RegistryCredentials.registry eq registry }.singleOrNull()?.let {
            RegistryCredentialRecord(it[RegistryCredentials.registry], it[RegistryCredentials.username], SecretValueCipher.decrypt(it[RegistryCredentials.passwordEncrypted]), it[RegistryCredentials.updatedAt])
        }
    }

    fun save(registry: String, username: String, password: String): ProviderCredentialMetadata = transaction {
        val now = LocalDateTime.now()
        val credential = ProviderCredentialRepository.createOrRotate(
            PROVIDER, TYPE, username, registry, Json.encodeToString(RegistryPayload(username, password))
        )

        // Compatibility copy for older workers/instances during the rollout.
        val encrypted = SecretValueCipher.encrypt(password)
        RegistryCredentials.upsert(RegistryCredentials.registry) {
            it[RegistryCredentials.registry] = registry; it[RegistryCredentials.username] = username; it[passwordEncrypted] = encrypted; it[updatedAt] = now
        }
        credential
    }

    fun delete(registry: String): Boolean = transaction {
        val removedProvider = ProviderCredentialRepository.retireCurrent(PROVIDER, TYPE, registry)
        val removedLegacy = RegistryCredentials.deleteWhere { RegistryCredentials.registry eq registry } > 0
        removedProvider || removedLegacy
    }
}
