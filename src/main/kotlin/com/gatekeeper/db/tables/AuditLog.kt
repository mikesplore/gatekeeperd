package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object AuditLog : Table("audit_log") {
    val id = uuid("id").autoGenerate()
    val projectId = uuid("project_id").references(Projects.id).nullable()
    val action = text("action")
    val actor = text("actor")
    val reason = text("reason").nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}