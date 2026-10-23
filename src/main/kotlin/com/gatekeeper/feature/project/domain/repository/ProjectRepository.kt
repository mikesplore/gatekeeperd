package com.gatekeeper.feature.project.domain.repository

import com.gatekeeper.db.tables.AccessBlockReason
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

interface ProjectRepository {
    data class ProjectRecord(
        val id: UUID,
        val slug: String,
        val name: String,
        val domain: String,
        val type: String,
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
        val blockReasonNote: String? = null
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

    fun findBySlug(slug: String, includeArchived: Boolean = false): ProjectRecord?
    fun findById(id: UUID): ProjectRecord?
    fun findActiveById(id: UUID): ProjectRecord?
    fun assignCustomer(id: UUID, customerId: UUID?): ProjectRecord?
    fun findAll(includeArchived: Boolean = false): List<ProjectRecord>
    fun findByCustomerId(customerId: UUID): List<ProjectRecord>
    fun create(
        slug: String, name: String, domain: String, type: String,
        billingName: String?, billingEmail: String?, billingAddress: String?, amountDue: BigDecimal?,
        currency: String, dueDate: LocalDate?, gracePeriodDays: Int,
        deploymentMode: String = "developer_hosted", serviceMode: String = "development",
        lifecycleStatus: String = "active", customerId: UUID? = null
    ): ProjectRecord
    fun update(
        slug: String, name: String?, domain: String?, type: String?,
        billingName: String? = null, billingEmail: String? = null, billingAddress: String? = null,
        amountDue: BigDecimal?, currency: String?, dueDate: LocalDate?, gracePeriodDays: Int?,
        clearAmountDue: Boolean = false, clearDueDate: Boolean = false,
        deploymentMode: String? = null, serviceMode: String? = null, lifecycleStatus: String? = null
    ): ProjectRecord?
    fun updateStatus(
        id: UUID, newStatus: String, actor: String, reason: String?, blockReason: String? = null,
        blockReasonCode: AccessBlockReason? = null, blockReasonNote: String? = null
    )
    fun archive(slug: String, actor: String, reason: String?): Boolean
    fun transfer(slug: String, deploymentMode: String, serviceMode: String, actor: String, reason: String?): ProjectRecord?
    fun delete(slug: String): Boolean
    fun invalidateCache(slug: String, projectId: UUID? = null)
    fun findOverdue(asOf: LocalDate): List<OverdueProject>
    fun setStatusOnly(id: UUID, newStatus: String, blockReason: String? = null)
    fun setStatusAndClearDueDate(id: UUID, newStatus: String, blockReason: String? = null)
    fun setStatusAndDueDate(id: UUID, newStatus: String, dueDate: LocalDate, blockReason: String? = null)
    fun findPastDue(asOf: LocalDate): List<ProjectRecord>
}
