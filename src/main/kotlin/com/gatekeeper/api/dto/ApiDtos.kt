package com.gatekeeper.api.dto

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import kotlinx.serialization.Serializable

@Serializable
data class ProjectResponse(
    val id: String,
    val slug: String,
    val name: String,
    val domain: String,
    val containerName: String,
    val type: String,
    val status: String,
    val clientName: String? = null,
    val clientEmail: String? = null,
    val paystackCustomerCode: String? = null,
    val amountDue: Double? = null,
    val currency: String,
    val dueDate: String? = null,
    val gracePeriodDays: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class PaymentResponse(
    val id: String,
    val projectId: String,
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
data class PaymentAdminResponse(
    val id: String,
    val projectId: String,
    val projectName: String,
    val projectSlug: String,
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
data class AuditLogResponse(
    val id: String,
    val projectId: String? = null,
    val action: String,
    val actor: String,
    val reason: String? = null,
    val createdAt: String
)

@Serializable
data class ProjectDetailResponse(
    val project: ProjectResponse,
    val payments: List<PaymentResponse>,
    val audit_log: List<AuditLogResponse>
)

@Serializable
data class StatusChangeResponse(
    val status: String,
    val slug: String
)

fun ProjectRepository.ProjectRecord.toResponse(): ProjectResponse = ProjectResponse(
    id = id.toString(),
    slug = slug,
    name = name,
    domain = domain,
    containerName = containerName,
    type = type,
    status = status,
    clientName = clientName,
    clientEmail = clientEmail,
    paystackCustomerCode = paystackCustomerCode,
    amountDue = amountDue?.toDouble(),
    currency = currency,
    dueDate = dueDate?.toString(),
    gracePeriodDays = gracePeriodDays,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

fun PaymentRepository.PaymentRecord.toResponse(): PaymentResponse = PaymentResponse(
    id = id.toString(),
    projectId = projectId.toString(),
    paystackReference = paystackReference,
    amount = amount.toDouble(),
    status = status,
    gatewayStatus = gatewayStatus,
    verifiedVia = verifiedVia,
    paidAt = paidAt?.toString(),
    rawWebhookPayload = rawWebhookPayload,
    createdAt = createdAt.toString()
)

fun AuditRepository.AuditRecord.toResponse(): AuditLogResponse = AuditLogResponse(
    id = id.toString(),
    projectId = projectId?.toString(),
    action = action,
    actor = actor,
    reason = reason,
    createdAt = createdAt.toString()
)
