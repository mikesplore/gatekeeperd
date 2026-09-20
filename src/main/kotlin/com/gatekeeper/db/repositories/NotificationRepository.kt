package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.Notifications
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

data class NotificationRecord(val id: UUID, val recipient: String, val projectId: UUID?, val title: String, val message: String, val severity: String, val action: String, val createdAt: LocalDateTime, val readAt: LocalDateTime?, val dismissedAt: LocalDateTime?, val archivedAt: LocalDateTime?)

object NotificationRepository {
    fun create(projectId: UUID?, action: String, actor: String, message: String?, createdAt: LocalDateTime = LocalDateTime.now()) = transaction {
        val severity = when { action.contains("failed", true) || action.contains("error", true) -> "error"; action.contains("blocked", true) || action.contains("warning", true) -> "warning"; else -> "info" }
        Notifications.insert { it[id] = UUID.randomUUID(); it[recipient] = "*"; it[Notifications.projectId] = projectId; it[title] = action.replace('_', ' '); it[Notifications.message] = message ?: "Activity recorded by $actor"; it[Notifications.severity] = severity; it[Notifications.action] = action; it[Notifications.createdAt] = createdAt }
    }

    fun list(recipient: String, severity: String?, action: String?, projectId: UUID?, from: LocalDateTime?, to: LocalDateTime?, includeArchived: Boolean, limit: Int, offset: Int = 0): List<NotificationRecord> = transaction {
        val query = Notifications.selectAll().where { (Notifications.recipient eq "*") or (Notifications.recipient eq recipient) }
        severity?.takeIf { it.isNotBlank() }?.let { query.andWhere { Notifications.severity eq it } }
        action?.takeIf { it.isNotBlank() }?.let { query.andWhere { Notifications.action eq it } }
        projectId?.let { query.andWhere { Notifications.projectId eq it } }
        from?.let { query.andWhere { Notifications.createdAt greaterEq it } }
        to?.let { query.andWhere { Notifications.createdAt lessEq it } }
        if (!includeArchived) query.andWhere { Notifications.archivedAt.isNull() and Notifications.dismissedAt.isNull() }
        query.orderBy(Notifications.createdAt to SortOrder.DESC).limit(limit.coerceIn(1, 100), offset.coerceAtLeast(0).toLong()).map { it.toRecord() }
    }

    fun count(recipient: String, severity: String?, action: String?, projectId: UUID?, from: LocalDateTime?, to: LocalDateTime?, includeArchived: Boolean): Long = transaction {
        val query = Notifications.selectAll().where { (Notifications.recipient eq "*") or (Notifications.recipient eq recipient) }
        severity?.takeIf { it.isNotBlank() }?.let { query.andWhere { Notifications.severity eq it } }; action?.takeIf { it.isNotBlank() }?.let { query.andWhere { Notifications.action eq it } }; projectId?.let { query.andWhere { Notifications.projectId eq it } }; from?.let { query.andWhere { Notifications.createdAt greaterEq it } }; to?.let { query.andWhere { Notifications.createdAt lessEq it } }; if (!includeArchived) query.andWhere { Notifications.archivedAt.isNull() and Notifications.dismissedAt.isNull() }; query.count()
    }

    fun mark(id: UUID, recipient: String, field: String): Boolean = transaction {
        val now = LocalDateTime.now()
        Notifications.update({ (Notifications.id eq id) and ((Notifications.recipient eq "*") or (Notifications.recipient eq recipient)) }) { row -> when (field) { "read" -> row[readAt] = now; "dismissed" -> row[dismissedAt] = now; "archived" -> row[archivedAt] = now } } > 0
    }

    private fun ResultRow.toRecord() = NotificationRecord(this[Notifications.id], this[Notifications.recipient], this[Notifications.projectId], this[Notifications.title], this[Notifications.message], this[Notifications.severity], this[Notifications.action], this[Notifications.createdAt], this[Notifications.readAt], this[Notifications.dismissedAt], this[Notifications.archivedAt])
}
