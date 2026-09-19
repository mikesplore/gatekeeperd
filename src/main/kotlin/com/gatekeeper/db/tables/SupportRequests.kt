package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object SupportRequests : Table("support_requests") {
    val id = uuid("id").autoGenerate()
    val projectId = uuid("project_id").references(Projects.id)
    val requesterName = text("requester_name").nullable()
    val requesterEmail = text("requester_email")
    val message = text("message")
    val status = text("status").default("open")
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
