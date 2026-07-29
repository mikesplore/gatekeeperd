package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.AuditLog
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object AuditRepository {

    data class AuditRecord(
        val id: UUID,
        val projectId: UUID?,
        val action: String,
        val actor: String,
        val reason: String?,
        val createdAt: java.time.LocalDateTime
    )

    fun write(projectId: UUID, action: String, actor: String, reason: String?) {
        transaction {
            AuditLog.insert {
                it[AuditLog.projectId] = projectId
                it[AuditLog.action] = action
                it[AuditLog.actor] = actor
                it[AuditLog.reason] = reason
            }
        }
    }

    fun findByProjectId(projectId: UUID): List<AuditRecord> {
        return transaction {
            AuditLog.selectAll()
                .where { AuditLog.projectId eq projectId }
                .orderBy(AuditLog.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .map { it.toAuditRecord() }
        }
    }

    fun findAll(limit: Int = 100): List<AuditRecord> {
        return transaction {
            AuditLog.selectAll()
                .orderBy(AuditLog.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { it.toAuditRecord() }
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toAuditRecord() = AuditRecord(
        id = this[AuditLog.id],
        projectId = this[AuditLog.projectId],
        action = this[AuditLog.action],
        actor = this[AuditLog.actor],
        reason = this[AuditLog.reason],
        createdAt = this[AuditLog.createdAt]
    )
}
