package com.gatekeeper.feature.audit.data.persistence

import com.gatekeeper.db.tables.AuditLog
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.notification.domain.repository.NotificationRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedAuditRepository(private val notifications: NotificationRepository) : AuditRepository {
    override fun write(projectId: UUID?, action: String, actor: String, reason: String?) {
        transaction {
            AuditLog.insert {
                it[AuditLog.projectId] = projectId
                it[AuditLog.action] = action
                it[AuditLog.actor] = actor
                it[AuditLog.reason] = reason
            }
            notifications.create(projectId, action, actor, reason)
        }
    }

    override fun findByProjectId(projectId: UUID): List<AuditRepository.Record> = transaction {
        AuditLog.selectAll().where { AuditLog.projectId eq projectId }
            .orderBy(AuditLog.createdAt, SortOrder.DESC).map { it.toRecord() }
    }

    override fun findAll(limit: Int): List<AuditRepository.Record> = transaction {
        AuditLog.selectAll().orderBy(AuditLog.createdAt, SortOrder.DESC).limit(limit).map { it.toRecord() }
    }

    override fun count(query: String?, action: String?): Long = transaction {
        val statement = AuditLog.selectAll()
        action?.takeIf { it.isNotBlank() }?.let { statement.andWhere { AuditLog.action eq it } }
        query?.takeIf { it.isNotBlank() }?.let {
            val pattern = "%${it.trim()}%"
            statement.andWhere { (AuditLog.actor like pattern) or (AuditLog.reason like pattern) or (AuditLog.action like pattern) }
        }
        statement.count()
    }

    override fun findPage(limit: Int, offset: Int, query: String?, action: String?, sort: String, direction: AuditRepository.SortDirection): List<AuditRepository.Record> = transaction {
        val statement = AuditLog.selectAll()
        action?.takeIf { it.isNotBlank() }?.let { statement.andWhere { AuditLog.action eq it } }
        query?.takeIf { it.isNotBlank() }?.let {
            val pattern = "%${it.trim()}%"
            statement.andWhere { (AuditLog.actor like pattern) or (AuditLog.reason like pattern) or (AuditLog.action like pattern) }
        }
        val column = when (sort) { "actor" -> AuditLog.actor; "action" -> AuditLog.action; else -> AuditLog.createdAt }
        statement.orderBy(column, if (direction == AuditRepository.SortDirection.ASC) SortOrder.ASC else SortOrder.DESC)
            .limit(limit.coerceIn(1, 500), offset.coerceAtLeast(0).toLong()).map { it.toRecord() }
    }

    override fun findFiltered(action: String?, actor: String?, limit: Int): List<AuditRepository.Record> = transaction {
        val query = AuditLog.selectAll()
        action?.takeIf { it.isNotBlank() }?.let { query.andWhere { AuditLog.action eq it } }
        actor?.takeIf { it.isNotBlank() }?.let { query.andWhere { AuditLog.actor eq it } }
        query.orderBy(AuditLog.createdAt, SortOrder.DESC).limit(limit.coerceIn(1, 5000)).map { it.toRecord() }
    }

    private fun ResultRow.toRecord() = AuditRepository.Record(this[AuditLog.id], this[AuditLog.projectId], this[AuditLog.action], this[AuditLog.actor], this[AuditLog.reason], this[AuditLog.createdAt])
}
