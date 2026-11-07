package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentRepository

class HandleMpesaCallback(
    private val decoder: MpesaCallbackDecoder,
    private val processPaymentEvent: ProcessPaymentEvent,
    private val payments: PaymentRepository
) {
    suspend operator fun invoke(rawPayload: String): ProcessPaymentEvent.Outcome {
        val callback = decoder.decode(rawPayload)
            ?: return processPaymentEvent(
                ProcessPaymentEvent.Command(
                    provider = "mpesa", eventType = "stk_callback",
                    dedupeKey = "mpesa-callback-invalid:${rawPayload.hashCode()}", rawPayload = rawPayload,
                    validationError = "Invalid M-Pesa callback payload"
                )
            )

        val payment = callback.reference?.let { payments.findByProviderReference("mpesa", it) }
        val status = when (callback.resultCode) {
            0 -> "success"
            1032 -> "abandoned"
            null -> null
            else -> "failed"
        }
        val validationError = when {
            callback.reference.isNullOrBlank() || callback.resultCode == null ->
                "M-Pesa callback is missing its request reference or result code"
            payment == null -> "M-Pesa callback does not match a pending payment"
            payment.status != "pending" && !(callback.resultCode == 0 && payment.status == "failed") ->
                "M-Pesa callback payment is no longer pending"
            callback.resultCode == 0 && callback.amount == null -> "Successful M-Pesa callback is missing its amount"
            callback.resultCode == 0 && callback.amount!!.compareTo(payment.amount) != 0 -> "M-Pesa callback amount does not match the initiated payment"
            callback.resultCode == 0 && callback.receiptNumber.isNullOrBlank() -> "Successful M-Pesa callback is missing its receipt number"
            else -> null
        }

        return processPaymentEvent(
            ProcessPaymentEvent.Command(
                provider = "mpesa", eventType = "stk_callback",
                dedupeKey = "mpesa-callback:${callback.reference.orEmpty()}:${callback.resultCode}",
                rawPayload = rawPayload, reference = callback.reference,
                status = status,
                verifiedVia = "callback",
                amount = callback.amount,
                currency = payment?.currency ?: "KES",
                paidAt = callback.paidAt,
                validationError = validationError
            )
        )
    }
}

data class MpesaCallbackData(
    val reference: String?,
    val resultCode: Int?,
    val amount: java.math.BigDecimal? = null,
    val receiptNumber: String? = null,
    val paidAt: java.time.LocalDateTime? = null
)

fun interface MpesaCallbackDecoder {
    fun decode(rawPayload: String): MpesaCallbackData?
}
