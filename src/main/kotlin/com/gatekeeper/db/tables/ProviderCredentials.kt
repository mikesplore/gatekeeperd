package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Encrypted provider credentials with stable identity and rotation/audit metadata. */
object ProviderCredentials : Table("provider_credentials") {
    val id = uuid("id")
    val provider = text("provider")
    val credentialType = text("credential_type")
    val displayName = text("display_name")
    val scope = text("scope")
    val version = integer("version")
    val encryptedPayload = text("encrypted_payload")
    val rotatedAt = datetime("rotated_at").nullable()
    val rotatedBy = text("rotated_by").nullable()
    val supersededAt = datetime("superseded_at").nullable()
    val supersededById = uuid("superseded_by_id").nullable()
    val createdAt = datetime("created_at")
    val createdBy = text("created_by").nullable()
    val updatedAt = datetime("updated_at")
    val updatedBy = text("updated_by").nullable()

    init {
        uniqueIndex("uq_provider_credentials_version", provider, credentialType, scope, version)
        index("idx_provider_credentials_provider_type", false, provider, credentialType)
    }

    override val primaryKey = PrimaryKey(id)
}
