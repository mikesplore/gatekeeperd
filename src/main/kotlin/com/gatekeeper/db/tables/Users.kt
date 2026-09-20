package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Users : Table("users") {
    val id = uuid("id").autoGenerate()
    val email = text("email").uniqueIndex()
    val displayName = text("display_name").nullable()
    val avatarUrl = text("avatar_url").nullable()
    val totpSecret = text("totp_secret").nullable()
    val totpEnabled = bool("totp_enabled").default(false)
    val recoveryCodes = array<String>("recovery_codes")
    val passwordHash = text("password_hash")
    val role = text("role").default("admin")
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
