package com.gatekeeper.feature.project.domain.repository

import com.gatekeeper.feature.project.domain.model.AdjustmentType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

interface ProjectAdjustmentRepository {
    data class Record(
        val id: UUID,
        val projectId: UUID,
        val serviceId: UUID?,
        val type: AdjustmentType,
        val amount: BigDecimal,
        val reason: String,
        val actor: String,
        val createdAt: LocalDateTime
    )

    fun create(projectId: UUID, type: AdjustmentType, amount: BigDecimal, reason: String, actor: String, serviceId: UUID? = null): Record
    fun totalForProject(projectId: UUID, type: AdjustmentType): BigDecimal
    fun findByProjectId(projectId: UUID): List<Record>
    fun totalForService(serviceId: UUID, type: AdjustmentType): BigDecimal
    fun findByServiceId(serviceId: UUID): List<Record>
}
