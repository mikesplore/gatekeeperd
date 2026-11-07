package com.gatekeeper.feature.project.domain.model

import com.gatekeeper.db.tables.AccessBlockReason
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class ProjectRecord(
    val id: UUID,
    val slug: String,
    val name: String,
    val domain: String,
    val status: String,
    val blockReason: String?,
    val deploymentMode: String,
    val serviceMode: String,
    val lifecycleStatus: String,
    val customerName: String?,
    val customerEmail: String?,
    val customerPhone: String?,
    val billingName: String?, val billingEmail: String?, val billingAddress: String?,
    val paystackCustomerCode: String?,
    val amountDue: BigDecimal?,
    val baseAmount: BigDecimal?,
    val currency: String,
    val dueDate: LocalDate?,
    val gracePeriodDays: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val customerId: UUID? = null,
    val blockReasonCode: AccessBlockReason? = null,
    val blockReasonNote: String? = null,
    val archived: Boolean = false
)

data class OverdueProject(
    val slug: String,
    val name: String,
    val customerName: String?,
    val customerEmail: String?,
    val customerPhone: String?,
    val billingName: String?, val billingEmail: String?, val billingAddress: String?,
    val dueDate: LocalDate,
    val daysOverdue: Long,
    val gracePeriodDays: Int,
    val willAutoBlockOn: LocalDate,
    val amountDue: BigDecimal?
)
