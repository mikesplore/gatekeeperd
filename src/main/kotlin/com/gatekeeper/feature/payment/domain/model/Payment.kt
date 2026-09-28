package com.gatekeeper.feature.payment.domain.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Framework-independent payment model used by payment use cases. */
data class Payment(
    val id: UUID,
    val projectId: UUID,
    val provider: String,
    val reference: String,
    val amount: BigDecimal,
    val status: String,
    val providerReference: String = reference,
    val currency: String? = null,
    val rawPayload: String? = null,
    val paidAt: LocalDateTime? = null,
    val recordStatus: String = status,
    val authorizationUrl: String? = null,
    val verifiedVia: String? = null,
    val createdAt: LocalDateTime? = null
) {
    val gatewayStatus: String get() = status
}

data class PaymentWithProject(val payment: Payment, val projectName: String, val projectSlug: String)
data class PaymentRevenueMonth(val month: String, val amount: BigDecimal)
data class PaymentCounts(val total: Long, val successful: Long, val pending: Long, val failed: Long)
