package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object PasswordResetTokens : Table("password_reset_tokens") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id")
    val tokenHash = text("token_hash").uniqueIndex()
    val expiresAt = datetime("expires_at")
    val usedAt = datetime("used_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
