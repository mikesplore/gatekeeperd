package com.gatekeeper.feature.payment.domain.gateway

import java.math.BigDecimal

interface PaymentGateway {
    val provider: String
    suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment>
    suspend fun verify(reference: String): Result<VerifiedPayment>
}

data class InitiatePaymentCommand(
    val projectId: java.util.UUID,
    val projectSlug: String,
    val email: String? = null,
    val phone: String? = null,
    val amount: BigDecimal,
    val currency: String,
    val callbackUrl: String? = null,
    val serviceId: java.util.UUID? = null
)

data class InitiatedPayment(val reference: String, val authorizationUrl: String? = null)

data class VerifiedPayment(
    val status: String,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val paidAt: java.time.LocalDateTime? = null
)
