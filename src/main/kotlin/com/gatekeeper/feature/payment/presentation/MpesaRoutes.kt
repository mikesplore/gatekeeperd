package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.payment.data.provider.MpesaCallback
import com.gatekeeper.feature.payment.data.provider.MpesaCallbackAck
import org.koin.ktor.ext.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent

private val json = Json { ignoreUnknownKeys = true }

fun Application.configureMpesaRoutes() {
    val processPaymentEvent = get<ProcessPaymentEvent>()
    routing {
        post("/api/mpesa/pay") {
            val slug = call.request.queryParameters["project"]
            val phone = call.request.queryParameters["phone"]
            val amountText = call.request.queryParameters["amount"]
            val amount = amountText?.toBigDecimalOrNull()
            if (slug.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "missing_payment_details", "project is required")
                return@post
            }
            if (amountText != null && amount == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "M-Pesa payment amount must be a whole KES amount")
                return@post
            }
            val initiated = call.application.get<InitiatePayment>()(
                InitiatePayment.Command("mpesa", slug, amount, phone = phone, currency = "KES")
            )
            initiated.fold(
                onSuccess = { result -> call.respond(HttpStatusCode.Accepted, mapOf("provider" to "mpesa", "reference" to result.reference, "status" to "pending")) },
                onFailure = { error ->
                    val missing = error.message == "Project not found"
                    val invalid = error is IllegalArgumentException || error is IllegalStateException
                    val status = when { missing -> HttpStatusCode.NotFound; invalid -> HttpStatusCode.BadRequest; else -> HttpStatusCode.BadGateway }
                    val code = when {
                        missing -> "project_not_found"
                        error.message?.startsWith("Enter a valid Kenyan mobile number") == true -> "invalid_mpesa_phone"
                        error.message == "A phone number is required for M-Pesa payments" -> "missing_payment_details"
                        invalid -> "invalid_payment_amount"
                        else -> "mpesa_unavailable"
                    }
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
