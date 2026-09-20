package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.ProjectAdjustments
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

object ProjectAdjustmentRepository {
    data class AdjustmentRecord(
        val id: UUID,
        val projectId: UUID,
        val type: AdjustmentType,
        val amount: BigDecimal,
        val reason: String,
        val actor: String,
        val createdAt: LocalDateTime
    )

    fun create(projectId: UUID, type: AdjustmentType, amount: BigDecimal, reason: String, actor: String): AdjustmentRecord {
        require(amount > BigDecimal.ZERO) { "Adjustment amount must be greater than zero" }
        require(reason.isNotBlank()) { "Adjustment reason is required" }
        require(actor.isNotBlank()) { "Adjustment actor is required" }
        return transaction {
            val id = UUID.randomUUID()
            ProjectAdjustments.insert {
                it[ProjectAdjustments.id] = id
                it[ProjectAdjustments.projectId] = projectId
                it[ProjectAdjustments.type] = type
                it[ProjectAdjustments.amount] = amount
                it[ProjectAdjustments.reason] = reason.trim()
                it[ProjectAdjustments.actor] = actor.trim()
            }
            ProjectAdjustments.select(ProjectAdjustments.columns).where { ProjectAdjustments.id eq id }.single().toRecord()
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() = AdjustmentRecord(
        id = this[ProjectAdjustments.id],
        projectId = this[ProjectAdjustments.projectId],
        type = this[ProjectAdjustments.type],
        amount = this[ProjectAdjustments.amount],
        reason = this[ProjectAdjustments.reason],
        actor = this[ProjectAdjustments.actor],
        createdAt = this[ProjectAdjustments.createdAt]
    )
}
