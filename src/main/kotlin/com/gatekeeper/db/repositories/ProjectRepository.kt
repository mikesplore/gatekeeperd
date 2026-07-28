package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.AuditLog
import com.gatekeeper.db.tables.ProjectStatus
import com.gatekeeper.db.tables.ProjectType
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.plugins.RedisService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

object ProjectRepository {

    private const val REDIS_KEY_PREFIX = "project:status:"

    data class ProjectRecord(
        val id: UUID,
        val slug: String,
        val name: String,
        val domain: String,
        val containerName: String,
        val type: String,
        val status: String,
        val clientName: String?,
        val clientEmail: String?,
        val paystackCustomerCode: String?,
        val amountDue: BigDecimal?,
        val currency: String,
        val dueDate: LocalDate?,
        val gracePeriodDays: Int,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    )

    fun findBySlug(slug: String): ProjectRecord? {
        return transaction {
            Projects.selectAll().where { Projects.slug eq slug }
                .singleOrNull()
                ?.toProjectRecord()
        }
    }

    fun findById(id: UUID): ProjectRecord? {
        return transaction {
            Projects.selectAll().where { Projects.id eq id }
                .singleOrNull()
                ?.toProjectRecord()
        }
    }

    fun findAll(): List<ProjectRecord> {
        return transaction {
            Projects.selectAll().orderBy(Projects.createdAt, SortOrder.DESC)
                .map { it.toProjectRecord() }
        }
    }

    fun create(
        slug: String,
        name: String,
        domain: String,
        containerName: String,
        type: String,
        clientName: String?,
        clientEmail: String?,
        amountDue: BigDecimal?,
        currency: String,
        dueDate: LocalDate?,
        gracePeriodDays: Int
    ): ProjectRecord {
        return transaction {
            val id = UUID.randomUUID()
            Projects.insert {
                it[Projects.id] = id
                it[Projects.slug] = slug
                it[Projects.name] = name
                it[Projects.domain] = domain
                it[Projects.containerName] = containerName
                it[Projects.type] = ProjectType.valueOf(type.uppercase())
                it[Projects.status] = ProjectStatus.ACTIVE
                it[Projects.clientName] = clientName
                it[Projects.clientEmail] = clientEmail
                it[Projects.amountDue] = amountDue
                it[Projects.currency] = currency
                it[Projects.dueDate] = dueDate
                it[Projects.gracePeriodDays] = gracePeriodDays
            }
            AuditLog.insert {
                it[AuditLog.projectId] = id
                it[AuditLog.action] = "project_created"
                it[AuditLog.actor] = "system"
                it[AuditLog.reason] = "Project $slug created"
            }
            findBySlug(slug)!!
        }
    }

    fun update(
        slug: String,
        name: String?,
        domain: String?,
        containerName: String?,
        type: String?,
        clientName: String?,
        clientEmail: String?,
        amountDue: BigDecimal?,
        currency: String?,
        dueDate: LocalDate?,
        gracePeriodDays: Int?
    ): ProjectRecord? {
        return transaction {
            val existing = Projects.selectAll().where { Projects.slug eq slug }.singleOrNull() ?: return@transaction null
            Projects.update({ Projects.slug eq slug }) {
                name?.let { v -> it[Projects.name] = v }
                domain?.let { v -> it[Projects.domain] = v }
                containerName?.let { v -> it[Projects.containerName] = v }
                type?.let { v -> it[Projects.type] = ProjectType.valueOf(v.uppercase()) }
                clientName?.let { v -> it[Projects.clientName] = v }
                clientEmail?.let { v -> it[Projects.clientEmail] = v }
                amountDue?.let { v -> it[Projects.amountDue] = v }
                currency?.let { v -> it[Projects.currency] = v }
                dueDate?.let { v -> it[Projects.dueDate] = v }
                gracePeriodDays?.let { v -> it[Projects.gracePeriodDays] = v }
            }
            AuditLog.insert {
                it[AuditLog.projectId] = existing[Projects.id]
                it[AuditLog.action] = "project_updated"
                it[AuditLog.actor] = "system"
                it[AuditLog.reason] = "Project $slug updated"
            }
            findBySlug(slug)
        }
    }

    fun updateStatus(id: UUID, newStatus: String, actor: String, reason: String?) {
        transaction {
            Projects.update({ Projects.id eq id }) {
                it[Projects.status] = ProjectStatus.valueOf(newStatus.uppercase())
            }
            AuditLog.insert {
                it[AuditLog.projectId] = id
                it[AuditLog.action] = when (newStatus.lowercase()) {
                    "blocked", "manual_block" -> "blocked"
                    "active" -> "unblocked"
                    else -> "manual_override"
                }
                it[AuditLog.actor] = actor
                it[AuditLog.reason] = reason
            }
        }
    }

    fun invalidateCache(slug: String) {
        try {
            RedisService.delete("$REDIS_KEY_PREFIX$slug")
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    fun findPastDue(asOf: LocalDate): List<ProjectRecord> {
        return transaction {
            Projects.selectAll()
                .where {
                    (Projects.status eq ProjectStatus.ACTIVE) and
                    Projects.dueDate.isNotNull()
                }
                .map { it.toProjectRecord() }
                .filter { record ->
                    record.dueDate != null &&
                    record.dueDate!!.plusDays(record.gracePeriodDays.toLong()).isBefore(asOf)
                }
        }
    }

    private fun ResultRow.toProjectRecord() = ProjectRecord(
        id = this[Projects.id],
        slug = this[Projects.slug],
        name = this[Projects.name],
        domain = this[Projects.domain],
        containerName = this[Projects.containerName],
        type = this[Projects.type].value,
        status = this[Projects.status].value,
        clientName = this[Projects.clientName],
        clientEmail = this[Projects.clientEmail],
        paystackCustomerCode = this[Projects.paystackCustomerCode],
        amountDue = this[Projects.amountDue],
        currency = this[Projects.currency],
        dueDate = this[Projects.dueDate],
        gracePeriodDays = this[Projects.gracePeriodDays],
        createdAt = this[Projects.createdAt],
        updatedAt = this[Projects.updatedAt]
    )
}