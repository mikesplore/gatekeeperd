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

object GitHubCredentialRepository {
    const val APP_PRIVATE_KEY = "app_private_key"
    const val WEBHOOK_SECRET = "webhook_secret"
    private const val PROVIDER = "github"
    private const val GLOBAL_SCOPE = "global"

    fun privateKeyPem(): String? = find(APP_PRIVATE_KEY)
    fun webhookSecret(): String? = find(WEBHOOK_SECRET)

    fun save(type: String, value: String, actor: String = "admin") = transaction {
        require(type == APP_PRIVATE_KEY || type == WEBHOOK_SECRET) { "Unsupported GitHub credential type" }
        require(value.isNotBlank()) { "Credential value must not be blank" }
        val now = LocalDateTime.now()
        val existing = ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq PROVIDER) and
                (ProviderCredentials.credentialType eq type) and
                (ProviderCredentials.scope eq GLOBAL_SCOPE)
        }.singleOrNull()
        val encrypted = SecretValueCipher.encrypt(value)
        val displayName = when (type) {
            APP_PRIVATE_KEY -> "GitHub App private key"
            else -> "GitHub webhook secret"
        }
        if (existing == null) {
            ProviderCredentials.insert {
                it[id] = UUID.randomUUID()
                it[provider] = PROVIDER
                it[credentialType] = type
                it[ProviderCredentials.displayName] = displayName
                it[scope] = GLOBAL_SCOPE
                it[encryptedPayload] = encrypted
                it[rotatedAt] = now
                it[rotatedBy] = actor
                it[createdAt] = now
                it[createdBy] = actor
                it[updatedAt] = now
                it[updatedBy] = actor
            }
        } else {
            ProviderCredentials.update({ ProviderCredentials.id eq existing[ProviderCredentials.id] }) {
                it[ProviderCredentials.displayName] = displayName
                it[encryptedPayload] = encrypted
                it[rotatedAt] = now
                it[rotatedBy] = actor
                it[updatedAt] = now
                it[updatedBy] = actor
            }
        }
    }

    private fun find(type: String): String? = transaction {
        ProviderCredentials.selectAll().where {
            (ProviderCredentials.provider eq PROVIDER) and
                (ProviderCredentials.credentialType eq type) and
                (ProviderCredentials.scope eq GLOBAL_SCOPE)
        }.singleOrNull()?.get(ProviderCredentials.encryptedPayload)?.let(SecretValueCipher::decrypt)
    }
}
