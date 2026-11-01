package com.gatekeeper.feature.service.domain.repository

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

interface ServiceInvoiceRepository {
    data class Record(
        val serviceId: UUID,
        val projectId: UUID,
        val description: String,
        val amount: BigDecimal,
        val currency: String,
        val dueDate: LocalDate?,
        val openingPaid: BigDecimal,
        val scribedInvoiceId: String?,
        val createdAt: LocalDateTime
    )

    fun findByServiceId(serviceId: UUID): Record?
    fun listByProjectId(projectId: UUID): List<Record>
    fun create(record: Record): Record
}
