package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.ProjectAdjustments
import com.gatekeeper.db.tables.Services
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and

object ProjectAdjustmentRepository {
    data class AdjustmentRecord(
        val id: UUID,
        val projectId: UUID,
        val serviceId: UUID?,
        val type: AdjustmentType,
        val amount: BigDecimal,
        val reason: String,
        val actor: String,
        val createdAt: LocalDateTime
    )

    fun create(projectId: UUID, type: AdjustmentType, amount: BigDecimal, reason: String, actor: String, serviceId: UUID? = null): AdjustmentRecord {
        require(amount > BigDecimal.ZERO) { "Adjustment amount must be greater than zero" }
        require(reason.isNotBlank()) { "Adjustment reason is required" }
        require(actor.isNotBlank()) { "Adjustment actor is required" }
        return transaction {
            if (serviceId != null) require(Services.selectAll().where { (Services.id eq serviceId) and (Services.projectId eq projectId) }.count() == 1L) {
                "Service does not belong to project"
            }
            val id = UUID.randomUUID()
            ProjectAdjustments.insert {
                it[ProjectAdjustments.id] = id
                it[ProjectAdjustments.projectId] = projectId
                it[ProjectAdjustments.serviceId] = serviceId
                it[ProjectAdjustments.type] = type
                it[ProjectAdjustments.amount] = amount
                it[ProjectAdjustments.reason] = reason.trim()
                it[ProjectAdjustments.actor] = actor.trim()
            }
            ProjectAdjustments.select(ProjectAdjustments.columns).where { ProjectAdjustments.id eq id }.single().toRecord()
        }
    }

    fun totalForProject(projectId: UUID, type: AdjustmentType): BigDecimal = transaction {
        ProjectAdjustments
            .select(ProjectAdjustments.amount)
            .where { (ProjectAdjustments.projectId eq projectId) and ProjectAdjustments.serviceId.isNull() and (ProjectAdjustments.type eq type) }
            .fold(BigDecimal.ZERO) { total, row -> total + row[ProjectAdjustments.amount] }
    }

    fun findByProjectId(projectId: UUID): List<AdjustmentRecord> = transaction {
        ProjectAdjustments.selectAll().where { (ProjectAdjustments.projectId eq projectId) and ProjectAdjustments.serviceId.isNull() }
            .orderBy(ProjectAdjustments.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { it.toRecord() }
    }

    fun totalForService(serviceId: UUID, type: AdjustmentType): BigDecimal = transaction {
        ProjectAdjustments.select(ProjectAdjustments.amount)
            .where { (ProjectAdjustments.serviceId eq serviceId) and (ProjectAdjustments.type eq type) }
            .fold(BigDecimal.ZERO) { total, row -> total + row[ProjectAdjustments.amount] }
    }

    fun findByServiceId(serviceId: UUID): List<AdjustmentRecord> = transaction {
        ProjectAdjustments.selectAll().where { ProjectAdjustments.serviceId eq serviceId }
            .orderBy(ProjectAdjustments.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { it.toRecord() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() = AdjustmentRecord(
        id = this[ProjectAdjustments.id],
        projectId = this[ProjectAdjustments.projectId],
        serviceId = this[ProjectAdjustments.serviceId],
        type = this[ProjectAdjustments.type],
        amount = this[ProjectAdjustments.amount],
        reason = this[ProjectAdjustments.reason],
        actor = this[ProjectAdjustments.actor],
        createdAt = this[ProjectAdjustments.createdAt]
    )
}
