package com.gatekeeper.payments

import java.math.BigDecimal
import java.time.LocalDateTime

enum class PaymentProvider {
    PAYSTACK,
    MPESA
}

enum class VerifiedPaymentStatus {
    SUCCESS,
    FAILED,
    ABANDONED,
    PENDING,
    REVERSED
}

data class VerifiedPayment(
    val provider: PaymentProvider,
    val reference: String,
    val status: VerifiedPaymentStatus,
    val amount: BigDecimal? = null,
    val currency: String? = null,
    val paidAt: LocalDateTime? = null,
    val rawPayload: String? = null
)

interface PaymentProviderClient {
    val provider: PaymentProvider
    suspend fun verify(reference: String): Result<VerifiedPayment>
}
