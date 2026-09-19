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
        val blockReason: String?,
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

    fun findBySlug(slug: String, includeArchived: Boolean = false): ProjectRecord? {
        return transaction {
            val row = if (includeArchived) {
                Projects.selectAll().where { Projects.slug eq slug }
            } else {
                Projects.selectAll().where { (Projects.slug eq slug) and Projects.deletedAt.isNull() }
            }
            row.singleOrNull()?.toProjectRecord()
        }
    }

    fun findById(id: UUID): ProjectRecord? {
        return transaction {
            Projects.selectAll().where { Projects.id eq id }
                .singleOrNull()
                ?.toProjectRecord()
        }
    }

    fun findAll(includeArchived: Boolean = false): List<ProjectRecord> {
        return transaction {
            val query = if (includeArchived) {
                Projects.selectAll()
            } else {
                Projects.selectAll().where { Projects.deletedAt.isNull() }
            }
            query.orderBy(Projects.createdAt, SortOrder.DESC)
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
        gracePeriodDays: Int?,
        clearClientName: Boolean = false,
        clearClientEmail: Boolean = false,
        clearAmountDue: Boolean = false,
        clearDueDate: Boolean = false
    ): ProjectRecord? {
        return transaction {
            val existing = Projects.selectAll()
                .where { (Projects.slug eq slug) and Projects.deletedAt.isNull() }
                .singleOrNull() ?: return@transaction null
            Projects.update({ Projects.slug eq slug }) {
                name?.let { v -> it[Projects.name] = v }
                domain?.let { v -> it[Projects.domain] = v }
                containerName?.let { v -> it[Projects.containerName] = v }
                type?.let { v -> it[Projects.type] = ProjectType.valueOf(v.uppercase()) }
                if (clearClientName) it[Projects.clientName] = null else clientName?.let { v -> it[Projects.clientName] = v }
                if (clearClientEmail) it[Projects.clientEmail] = null else clientEmail?.let { v -> it[Projects.clientEmail] = v }
                if (clearAmountDue) it[Projects.amountDue] = null else amountDue?.let { v -> it[Projects.amountDue] = v }
                currency?.let { v -> it[Projects.currency] = v }
                if (clearDueDate) it[Projects.dueDate] = null else dueDate?.let { v -> it[Projects.dueDate] = v }
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

    fun updateStatus(id: UUID, newStatus: String, actor: String, reason: String?, blockReason: String? = null) {
        val slug = transaction {
            val project = Projects.selectAll().where { Projects.id eq id }.singleOrNull()
                ?: return@transaction null
            Projects.update({ Projects.id eq id }) {
                it[Projects.status] = ProjectStatus.valueOf(newStatus.uppercase())
                it[Projects.blockReason] = if (newStatus.equals("active", ignoreCase = true)) null else blockReason
                it[Projects.updatedAt] = LocalDateTime.now()
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
            project[Projects.slug]
        }
        slug?.let(::invalidateCache)
    }

    fun archive(slug: String, actor: String, reason: String?): Boolean {
        return transaction {
            val project = Projects.selectAll()
                .where { (Projects.slug eq slug) and Projects.deletedAt.isNull() }
                .singleOrNull()
                ?: return@transaction false

            val now = LocalDateTime.now()
            val previousContainerName = project[Projects.containerName]
            Projects.update({ Projects.id eq project[Projects.id] }) {
                it[Projects.deletedAt] = now
                it[Projects.status] = ProjectStatus.BLOCKED
                it[Projects.containerName] = "archived-$slug"
                it[Projects.updatedAt] = now
            }
            AuditLog.insert {
                it[AuditLog.projectId] = project[Projects.id]
                it[AuditLog.action] = "project_archived"
                it[AuditLog.actor] = actor
                it[AuditLog.reason] = reason ?: "Project archived (soft delete). Unlinked container '$previousContainerName'."
            }
            true
        }.also { archived ->
            if (archived) invalidateCache(slug)
        }
    }

    @Deprecated("Use archive — hard delete removed to preserve payment/audit history", ReplaceWith("archive(slug, actor, reason)"))
    fun delete(slug: String): Boolean = archive(slug, "system", "legacy delete call")

    fun invalidateCache(slug: String) {
        try {
            RedisService.delete("$REDIS_KEY_PREFIX$slug")
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    data class OverdueProject(
        val slug: String,
        val name: String,
        val clientName: String?,
        val clientEmail: String?,
        val dueDate: LocalDate,
        val daysOverdue: Long,
        val gracePeriodDays: Int,
        val willAutoBlockOn: LocalDate,
        val amountDue: BigDecimal?
    )

    fun findOverdue(asOf: LocalDate): List<OverdueProject> {
        return transaction {
            Projects.selectAll()
                .where {
                    Projects.deletedAt.isNull() and
                        (Projects.status eq ProjectStatus.ACTIVE) and
                        Projects.dueDate.isNotNull() and
                        (Projects.dueDate less asOf)
                }
                .map { row ->
                    val dueDate = row[Projects.dueDate]!!
                    val grace = row[Projects.gracePeriodDays]
                    OverdueProject(
                        slug = row[Projects.slug],
                        name = row[Projects.name],
                        clientName = row[Projects.clientName],
                        clientEmail = row[Projects.clientEmail],
                        dueDate = dueDate,
                        daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(dueDate, asOf),
                        gracePeriodDays = grace,
                        willAutoBlockOn = dueDate.plusDays(grace.toLong()),
                        amountDue = row[Projects.amountDue]
                    )
                }
                .sortedByDescending { it.daysOverdue }
        }
    }

    fun setStatusOnly(id: UUID, newStatus: String, blockReason: String? = null) {
        val slug = transaction {
            val project = Projects.selectAll().where { Projects.id eq id }.singleOrNull()
                ?: return@transaction null
            Projects.update({ Projects.id eq id }) {
                it[Projects.status] = ProjectStatus.valueOf(newStatus.uppercase())
                it[Projects.blockReason] = if (newStatus.equals("active", ignoreCase = true)) null else blockReason
                it[Projects.updatedAt] = LocalDateTime.now()
            }
            project[Projects.slug]
        }
        slug?.let(::invalidateCache)
    }

    fun setStatusAndClearDueDate(id: UUID, newStatus: String, blockReason: String? = null) {
        val slug = transaction {
            val project = Projects.selectAll().where { Projects.id eq id }.singleOrNull()
                ?: return@transaction null
            Projects.update({ Projects.id eq id }) {
                it[Projects.status] = ProjectStatus.valueOf(newStatus.uppercase())
                it[Projects.blockReason] = if (newStatus.equals("active", ignoreCase = true)) null else blockReason
                it[Projects.dueDate] = null
                it[Projects.updatedAt] = LocalDateTime.now()
            }
            project[Projects.slug]
        }
        slug?.let(::invalidateCache)
    }

    fun setStatusAndDueDate(id: UUID, newStatus: String, dueDate: LocalDate, blockReason: String? = null) {
        val slug = transaction {
            val project = Projects.selectAll().where { Projects.id eq id }.singleOrNull()
                ?: return@transaction null
            Projects.update({ Projects.id eq id }) {
                it[Projects.status] = ProjectStatus.valueOf(newStatus.uppercase())
                it[Projects.blockReason] = if (newStatus.equals("active", ignoreCase = true)) null else blockReason
                it[Projects.dueDate] = dueDate
                it[Projects.updatedAt] = LocalDateTime.now()
            }
            project[Projects.slug]
        }
        slug?.let(::invalidateCache)
    }

    fun findPastDue(asOf: LocalDate): List<ProjectRecord> {
        return transaction {
            Projects.selectAll()
                .where {
                    Projects.deletedAt.isNull() and
                        (Projects.status eq ProjectStatus.ACTIVE) and
                        Projects.dueDate.isNotNull()
                }
                .map { it.toProjectRecord() }
                .filter { record ->
                    record.dueDate != null &&
                    record.dueDate.plusDays(record.gracePeriodDays.toLong()).isBefore(asOf)
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
        blockReason = this[Projects.blockReason],
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
