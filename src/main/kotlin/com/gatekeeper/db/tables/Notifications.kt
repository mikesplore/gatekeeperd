package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Notifications : Table("notifications") {
    val id = uuid("id")
    val recipient = text("recipient")
    val projectId = uuid("project_id").nullable()
    val title = text("title")
    val message = text("message")
    val severity = text("severity")
    val action = text("action")
    val createdAt = datetime("created_at")
    val readAt = datetime("read_at").nullable()
    val dismissedAt = datetime("dismissed_at").nullable()
    val archivedAt = datetime("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
