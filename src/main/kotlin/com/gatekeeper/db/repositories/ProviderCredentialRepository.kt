package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.ProviderCredentials
import com.gatekeeper.security.SecretValueCipher
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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

object ProviderCredentialRepository {
    fun listMetadata(provider: String? = null, credentialType: String? = null): List<ProviderCredentialMetadata> = transaction {
        ProviderCredentials.selectAll().toList()
            .filter { provider == null || it[ProviderCredentials.provider] == provider }
            .filter { credentialType == null || it[ProviderCredentials.credentialType] == credentialType }
            .map(::metadata)
            .sortedWith(compareBy<ProviderCredentialMetadata>({ it.provider }, { it.credentialType }, { it.scope }, { it.version }))
    }

    fun findMetadata(id: UUID): ProviderCredentialMetadata? = transaction {
        ProviderCredentials.selectAll().where { ProviderCredentials.id eq id }.singleOrNull()?.let(::metadata)
    }

    fun findById(id: UUID): StoredProviderCredential? = transaction {
        ProviderCredentials.selectAll().where { ProviderCredentials.id eq id }.singleOrNull()?.let { row ->
            StoredProviderCredential(metadata(row), SecretValueCipher.decrypt(row[ProviderCredentials.encryptedPayload]))
        }
    }

    fun findCurrent(provider: String, credentialType: String, scope: String): StoredProviderCredential? = transaction {
        ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq provider) and
                (ProviderCredentials.credentialType eq credentialType) and
                (ProviderCredentials.scope eq scope) and
                ProviderCredentials.supersededAt.isNull()
        }.singleOrNull()?.let { row ->
            StoredProviderCredential(metadata(row), SecretValueCipher.decrypt(row[ProviderCredentials.encryptedPayload]))
        }
    }

    fun retireCurrent(provider: String, credentialType: String, scope: String, actor: String = "admin"): Boolean = transaction {
        val current = ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq provider) and
                (ProviderCredentials.credentialType eq credentialType) and
                (ProviderCredentials.scope eq scope) and
                ProviderCredentials.supersededAt.isNull()
        }.singleOrNull() ?: return@transaction false
        val now = LocalDateTime.now()
        ProviderCredentials.update({ ProviderCredentials.id eq current[ProviderCredentials.id] }) {
            it[ProviderCredentials.supersededAt] = now
            it[ProviderCredentials.updatedAt] = now
            it[ProviderCredentials.updatedBy] = actor
        }
        true
    }

    /** Inserts a new immutable version and marks the prior current row as superseded. */
    fun createOrRotate(
        provider: String,
        credentialType: String,
        displayName: String,
        scope: String,
        payload: String,
        actor: String = "admin"
    ): ProviderCredentialMetadata = transaction {
        require(provider in setOf("docker", "github")) { "Unsupported credential provider" }
        require((provider == "docker" && credentialType == "registry") ||
            (provider == "github" && credentialType in setOf("app_private_key", "webhook_secret"))) {
            "Unsupported credential type"
        }
        val current = ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq provider) and
                (ProviderCredentials.credentialType eq credentialType) and
                (ProviderCredentials.scope eq scope) and
                ProviderCredentials.supersededAt.isNull()
        }.singleOrNull()
        val nextVersion = (ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq provider) and
                (ProviderCredentials.credentialType eq credentialType) and
                (ProviderCredentials.scope eq scope)
        }.map { it[ProviderCredentials.version] }.maxOrNull() ?: 0) + 1
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        val encrypted = SecretValueCipher.encrypt(payload)

        current?.let { old ->
            ProviderCredentials.update({ ProviderCredentials.id eq old[ProviderCredentials.id] }) {
                it[ProviderCredentials.supersededAt] = now
                it[ProviderCredentials.supersededById] = id
                it[ProviderCredentials.updatedAt] = now
                it[ProviderCredentials.updatedBy] = actor
            }
        }
        ProviderCredentials.insert {
            it[ProviderCredentials.id] = id
            it[ProviderCredentials.provider] = provider
            it[ProviderCredentials.credentialType] = credentialType
            it[ProviderCredentials.displayName] = displayName
            it[ProviderCredentials.scope] = scope
            it[ProviderCredentials.version] = nextVersion
            it[ProviderCredentials.encryptedPayload] = encrypted
            it[ProviderCredentials.rotatedAt] = if (current == null) null else now
            it[ProviderCredentials.rotatedBy] = if (current == null) null else actor
            it[ProviderCredentials.supersededAt] = null
            it[ProviderCredentials.supersededById] = null
            it[ProviderCredentials.createdAt] = now
            it[ProviderCredentials.createdBy] = actor
            it[ProviderCredentials.updatedAt] = now
            it[ProviderCredentials.updatedBy] = actor
        }
        ProviderCredentialMetadata(
            id, provider, credentialType, displayName, scope, nextVersion,
            if (current == null) null else now, if (current == null) null else actor,
            null, null, now, actor, now, actor
        )
    }

    private fun metadata(row: org.jetbrains.exposed.sql.ResultRow) = ProviderCredentialMetadata(
        row[ProviderCredentials.id], row[ProviderCredentials.provider], row[ProviderCredentials.credentialType],
        row[ProviderCredentials.displayName], row[ProviderCredentials.scope], row[ProviderCredentials.version],
        row[ProviderCredentials.rotatedAt], row[ProviderCredentials.rotatedBy], row[ProviderCredentials.supersededAt],
        row[ProviderCredentials.supersededById], row[ProviderCredentials.createdAt], row[ProviderCredentials.createdBy],
        row[ProviderCredentials.updatedAt], row[ProviderCredentials.updatedBy]
    )
}
