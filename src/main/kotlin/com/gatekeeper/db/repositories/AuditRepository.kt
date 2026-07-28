package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.AuditLog
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.*

object AuditRepository {

    data class AuditRecord(
        val id: UUID,
        val projectId: UUID?,
        val action: String,
        val actor: String,
        val reason: String?,
        val createdAt: LocalDateTime
    )

    fun findByProjectId(projectId: UUID): List<AuditRecord> {
        return transaction {
            AuditLog.selectAll()
                .where { AuditLog.projectId eq projectId }
                .orderBy(AuditLog.createdAt, SortOrder.DESC)
                .map { it.toAuditRecord() }
        }
    }

    fun findAll(limit: Int = 100): List<AuditRecord> {
        return transaction {
            AuditLog.selectAll()
                .orderBy(AuditLog.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toAuditRecord() }
        }
    }

    private fun ResultRow.toAuditRecord() = AuditRecord(
        id = this[AuditLog.id],
        projectId = this[AuditLog.projectId],
        action = this[AuditLog.action],
        actor = this[AuditLog.actor],
        reason = this[AuditLog.reason],
        createdAt = this[AuditLog.createdAt]
    )
}