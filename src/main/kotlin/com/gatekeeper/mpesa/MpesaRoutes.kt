package com.gatekeeper.mpesa

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent
import org.koin.ktor.ext.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.math.BigDecimal

private val json = Json { ignoreUnknownKeys = true }

fun Application.configureMpesaRoutes() {
    val processPaymentEvent = get<ProcessPaymentEvent>()
    routing {
        post("/api/mpesa/pay") {
            val slug = call.request.queryParameters["project"]
            val phone = call.request.queryParameters["phone"]
            val amountText = call.request.queryParameters["amount"]
            val amount = amountText?.toBigDecimalOrNull()
            if (slug.isNullOrBlank() || phone.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "missing_payment_details", "project and phone are required")
                return@post
            }
            val normalizedPhone = MpesaPhoneNumber.normalize(phone)
            if (normalizedPhone == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_mpesa_phone", "Enter a valid Kenyan mobile number, such as 0712345678 or 254712345678")
                return@post
            }
            if (amountText != null && (amount == null || amount <= BigDecimal.ZERO || amount.scale().coerceAtLeast(0) > 2)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be greater than zero and have at most two decimal places")
                return@post
            }
            if (amount != null && amount.stripTrailingZeros().scale() > 0) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "M-Pesa payment amount must be a whole KES amount")
                return@post
            }
            val initiated = call.application.get<InitiatePayment>()(
                InitiatePayment.Command("mpesa", slug, amount, phone = normalizedPhone, currency = "KES")
            )
            initiated.fold(
                onSuccess = { result -> call.respond(HttpStatusCode.Accepted, mapOf("provider" to "mpesa", "reference" to result.reference, "status" to "pending")) },
                onFailure = { error ->
                    val missing = error.message == "Project not found"
                    val invalid = error is IllegalArgumentException || error is IllegalStateException
                    val status = when { missing -> HttpStatusCode.NotFound; invalid -> HttpStatusCode.BadRequest; else -> HttpStatusCode.BadGateway }
                    val code = when { missing -> "project_not_found"; invalid -> "invalid_payment_amount"; else -> "mpesa_unavailable" }
                    call.respondError(status, code, error.message ?: "Unable to initiate M-Pesa payment")
                }
            )
        }

        post("/api/mpesa/callback") {
            val raw = call.receiveText()
            val callback = runCatching { json.decodeFromString<MpesaCallback>(raw) }.getOrNull()
            val result = callback?.Body?.stkCallback
            if (result == null) {
                processPaymentEvent(
                    ProcessPaymentEvent.Command(
                        provider = "mpesa", eventType = "stk_callback", dedupeKey = "mpesa-callback-invalid:${raw.hashCode()}",
                        rawPayload = raw, validationError = "Invalid M-Pesa callback payload"
                    )
                )
                call.respond(HttpStatusCode.OK, MpesaCallbackAck())
                return@post
            }
            val reference = result.CheckoutRequestID
            val amount = result.CallbackMetadata?.Item?.firstOrNull { it.Name == "Amount" }?.Value?.toString()?.trim('"')?.toBigDecimalOrNull()
            val error = when {
                reference.isNullOrBlank() || result.ResultCode == null -> "M-Pesa callback is missing its request reference or result code"
                else -> null
            }
            processPaymentEvent(
                ProcessPaymentEvent.Command(
                    provider = "mpesa", eventType = "stk_callback",
                    dedupeKey = "mpesa-callback:${reference.orEmpty()}:${result.ResultCode}", rawPayload = raw,
                    reference = reference, status = if (error == null) if (result.ResultCode == 0) "success" else "failed" else null,
                    verifiedVia = "webhook", amount = amount, validationError = error
                )
            )
            call.respond(HttpStatusCode.OK, MpesaCallbackAck())
        }
    }
}
