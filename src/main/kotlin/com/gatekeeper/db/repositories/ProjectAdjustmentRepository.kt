package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.ProjectAdjustments
import com.gatekeeper.feature.project.data.persistence.ExposedProjectAdjustmentRepository
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Deprecated("Inject ProjectAdjustmentRepository instead")
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

    private val delegate: ProjectAdjustmentStore by lazy { ExposedProjectAdjustmentRepository() }

    fun create(projectId: UUID, type: AdjustmentType, amount: BigDecimal, reason: String, actor: String, serviceId: UUID? = null): AdjustmentRecord =
        delegate.create(projectId, type, amount, reason, actor, serviceId).toLegacy()

    fun totalForProject(projectId: UUID, type: AdjustmentType): BigDecimal = delegate.totalForProject(projectId, type)

    fun findByProjectId(projectId: UUID): List<AdjustmentRecord> = delegate.findByProjectId(projectId).map { it.toLegacy() }

    fun totalForService(serviceId: UUID, type: AdjustmentType): BigDecimal = delegate.totalForService(serviceId, type)

    fun findByServiceId(serviceId: UUID): List<AdjustmentRecord> = delegate.findByServiceId(serviceId).map { it.toLegacy() }

    private fun ProjectAdjustmentStore.Record.toLegacy() = AdjustmentRecord(id, projectId, serviceId, type, amount, reason, actor, createdAt)
}
