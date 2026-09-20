package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object RegistryCredentials : Table("registry_credentials") {
    val registry = varchar("registry", 255)
    val username = varchar("username", 255)
    val passwordEncrypted = text("password_encrypted")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(registry)
}
