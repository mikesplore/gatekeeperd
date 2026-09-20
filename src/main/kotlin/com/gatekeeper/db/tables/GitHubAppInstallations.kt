package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object GitHubAppInstallations : Table("github_app_installation") {
    val id = integer("id")
    val installationId = long("installation_id")
    val accountLogin = text("account_login").nullable()
    val accountType = text("account_type").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

