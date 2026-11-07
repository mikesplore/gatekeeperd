package com.gatekeeper.feature.payment.domain.usecase

class HandleMpesaCallback(
    private val decoder: MpesaCallbackDecoder,
    private val processPaymentEvent: ProcessPaymentEvent,
    private val paymentVerifier: MpesaPaymentVerifier
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

        val validationError = when {
            callback.reference.isNullOrBlank() || callback.resultCode == null ->
                "M-Pesa callback is missing its request reference or result code"
            else -> null
        }

        val outcome = processPaymentEvent(
            ProcessPaymentEvent.Command(
                provider = "mpesa", eventType = "stk_callback",
                dedupeKey = "mpesa-callback:${callback.reference.orEmpty()}:${callback.resultCode}",
                rawPayload = rawPayload, reference = callback.reference,
                // Callback data is retained for audit, but never trusted to
                // settle a payment. Daraja's STK query is authoritative.
                status = null,
                verifiedVia = "webhook", validationError = validationError
            )
        )
        if (validationError == null) paymentVerifier.verify(callback.reference!!, callback.resultCode!!)
        return outcome
    }
}

fun interface MpesaPaymentVerifier {
    suspend fun verify(reference: String, callbackResultCode: Int)
}

data class MpesaCallbackData(val reference: String?, val resultCode: Int?)

fun interface MpesaCallbackDecoder {
    fun decode(rawPayload: String): MpesaCallbackData?
}
