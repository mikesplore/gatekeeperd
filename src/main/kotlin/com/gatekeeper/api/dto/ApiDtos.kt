package com.gatekeeper.api.dto

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.payments.ProjectBalanceService
import kotlinx.serialization.Serializable

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val NairobiZone: ZoneId = ZoneId.of("Africa/Nairobi")
private fun LocalDateTime.toNairobiTimestamp(): String =
    atZone(NairobiZone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

@Serializable
data class PaginatedResponse<T>(val data: List<T>, val total: Long, val limit: Int, val offset: Int, val hasMore: Boolean)

@Serializable
data class ProjectResponse(
    val id: String,
    val slug: String,
    val name: String,
    val domain: String,
    val containerName: String,
    val type: String,
    val status: String,
    val blockReason: String? = null,
    val deploymentMode: String,
    val serviceMode: String,
    val lifecycleStatus: String,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val paystackCustomerCode: String? = null,
    val amountDue: Double? = null,
    val baseAmount: Double? = null,
    val additionalCharges: Double = 0.0,
    val discounts: Double = 0.0,
    val successfulPayments: Double = 0.0,
    val remainingBalance: Double? = null,
    val currency: String,
    val dueDate: String? = null,
    val gracePeriodDays: Int,
    val createdAt: String,
    val updatedAt: String,
    val githubRepository: String? = null,
    val githubRef: String = "main",
    val deployImageName: String? = null,
    val deployImageTag: String = "latest",
    val autoDeploy: Boolean = false
)

@Serializable
data class PaymentResponse(
    val id: String,
    val projectId: String,
    val provider: String,
    val providerReference: String,
    val paystackReference: String,
    val amount: Double,
    val status: String,
    val gatewayStatus: String,
    val verifiedVia: String? = null,
    val paidAt: String? = null,
    val rawWebhookPayload: String? = null,
    val createdAt: String
)

@Serializable
data class ProjectAdjustmentResponse(
    val id: String,
    val projectId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val actor: String,
    val createdAt: String
)

fun ProjectAdjustmentRepository.AdjustmentRecord.toResponse() = ProjectAdjustmentResponse(
    id = id.toString(), projectId = projectId.toString(), type = type.name,
    amount = amount.toDouble(), reason = reason, actor = actor, createdAt = createdAt.toNairobiTimestamp()
)

@Serializable
data class PaymentAdminResponse(
    val id: String,
    val projectId: String,
    val projectName: String,
    val projectSlug: String,
    val provider: String,
    val providerReference: String,
    val paystackReference: String,
    val amount: Double,
    val gatewayStatus: String,
    val verifiedVia: String? = null,
    val paidAt: String? = null,
    val createdAt: String
)

@Serializable
data class PaymentsListResponse(
    val payments: List<PaymentAdminResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int
)

@Serializable
data class ProjectsListResponse(
    val projects: List<ProjectResponse>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

@Serializable
data class OverdueProjectResponse(
    val slug: String,
    val name: String,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val dueDate: String,
    val daysOverdue: Long,
    val gracePeriodDays: Int,
    val willAutoBlockOn: String,
    val amountDue: Double
)

@Serializable
data class RevenueMonthResponse(
    val month: String,
    val amount: Double
)

@Serializable
data class RevenueReportResponse(
    val totalThisMonth: Double,
    val totalLastMonth: Double,
    val currency: String,
    val byMonth: List<RevenueMonthResponse>
)

@Serializable
data class PaymentEventAdminResponse(
    val id: String,
    val dedupeKey: String? = null,
    val eventType: String,
    val paystackReference: String? = null,
    val processingStatus: String,
    val processingAttempts: Int,
    val processingError: String? = null,
    val receivedAt: String,
    val processedAt: String? = null
)

@Serializable
data class AuditLogResponse(
    val id: String,
    val projectId: String? = null,
    val action: String,
    val actor: String,
    val reason: String? = null,
    val createdAt: String
)

@Serializable
data class AuditLogPageResponse(val entries: List<AuditLogResponse>, val total: Long, val limit: Int, val offset: Int, val hasMore: Boolean)

@Serializable
data class ProjectDetailResponse(
    val project: ProjectResponse,
    val payments: List<PaymentResponse>,
    val audit_log: List<AuditLogResponse>,
    val adjustments: List<ProjectAdjustmentResponse> = emptyList()
)

@Serializable
data class StatusChangeResponse(
    val status: String,
    val slug: String
)

fun ProjectRepository.ProjectRecord.toResponse(remainingBalance: BigDecimal? = null): ProjectResponse = ProjectResponse(
    id = id.toString(),
    slug = slug,
    name = name,
    domain = domain,
    containerName = containerName,
    type = type,
    status = status,
    blockReason = blockReason,
    deploymentMode = deploymentMode,
    serviceMode = serviceMode,
    lifecycleStatus = lifecycleStatus,
    clientName = clientName,
    clientEmail = clientEmail,
    paystackCustomerCode = paystackCustomerCode,
    amountDue = amountDue?.toDouble(),
    baseAmount = baseAmount?.toDouble(),
    additionalCharges = ProjectBalanceService.additionalCharges(this).toDouble(),
    discounts = ProjectBalanceService.discounts(this).toDouble(),
    successfulPayments = ProjectBalanceService.successfulPayments(this).toDouble(),
    remainingBalance = remainingBalance?.toDouble(),
    currency = currency,
    dueDate = dueDate?.toString(),
    gracePeriodDays = gracePeriodDays,
    createdAt = createdAt.toNairobiTimestamp(),
    updatedAt = updatedAt.toNairobiTimestamp(),
    githubRepository = githubRepository,
    githubRef = githubRef,
    deployImageName = deployImageName,
    deployImageTag = deployImageTag,
    autoDeploy = autoDeploy
)

fun PaymentRepository.PaymentRecord.toResponse(): PaymentResponse = PaymentResponse(
    id = id.toString(),
    projectId = projectId.toString(),
    provider = provider.name.lowercase(),
    providerReference = providerReference,
    paystackReference = paystackReference,
    amount = amount.toDouble(),
    status = status,
    gatewayStatus = gatewayStatus,
    verifiedVia = verifiedVia,
    paidAt = paidAt?.toNairobiTimestamp(),
    rawWebhookPayload = rawWebhookPayload,
    createdAt = createdAt.toNairobiTimestamp()
)

fun AuditRepository.AuditRecord.toResponse(): AuditLogResponse = AuditLogResponse(
    id = id.toString(),
    projectId = projectId?.toString(),
    action = action,
    actor = actor,
    reason = reason,
    createdAt = createdAt.toNairobiTimestamp()
)
