package com.gatekeeper.feature.payment.domain.usecase

import java.math.BigDecimal

class HandleMpesaCallback(
    private val decoder: MpesaCallbackDecoder,
    private val processPaymentEvent: ProcessPaymentEvent
) {
    operator fun invoke(rawPayload: String): ProcessPaymentEvent.Outcome {
        val callback = decoder.decode(rawPayload)
            ?: return processPaymentEvent(
                ProcessPaymentEvent.Command(
                    provider = "mpesa", eventType = "stk_callback",
                    dedupeKey = "mpesa-callback-invalid:${rawPayload.hashCode()}", rawPayload = rawPayload,
                    validationError = "Invalid M-Pesa callback payload"
                )
            )

        val validationError = when {
            callback.reference.isNullOrBlank() || callback.resultCode == null ->
                "M-Pesa callback is missing its request reference or result code"
            else -> null
        }

        return processPaymentEvent(
            ProcessPaymentEvent.Command(
                provider = "mpesa", eventType = "stk_callback",
                dedupeKey = "mpesa-callback:${callback.reference.orEmpty()}:${callback.resultCode}",
                rawPayload = rawPayload, reference = callback.reference,
                status = if (validationError == null) if (callback.resultCode == 0) "success" else "failed" else null,
                verifiedVia = "webhook", amount = callback.amount, validationError = validationError
            )
        )
    }
}

data class MpesaCallbackData(val reference: String?, val resultCode: Int?, val amount: BigDecimal?)

fun interface MpesaCallbackDecoder {
    fun decode(rawPayload: String): MpesaCallbackData?
}
