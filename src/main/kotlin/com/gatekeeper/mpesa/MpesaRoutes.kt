package com.gatekeeper.mpesa

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.PaymentEventRepository
import com.gatekeeper.payments.ProjectBalanceService
import com.gatekeeper.payments.PaymentApplicationService
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import org.koin.ktor.ext.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

fun Application.configureMpesaRoutes() {
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
                val eventId = PaymentEventRepository.recordIfNew(
                    dedupeKey = "mpesa-callback:${UUID.randomUUID()}", eventType = "stk_callback",
                    rawPayload = raw, projectId = null, paymentId = null, paystackReference = null, provider = "mpesa"
                )
                eventId?.let { PaymentEventRepository.markFailed(it, "Invalid M-Pesa callback payload") }
                call.respond(HttpStatusCode.OK, MpesaCallbackAck())
                return@post
            }
            val reference = result.CheckoutRequestID
            val payment = reference?.let { com.gatekeeper.db.repositories.PaymentRepository.findByProviderReference(com.gatekeeper.payments.PaymentProvider.MPESA, it) }
            val eventId = PaymentEventRepository.recordIfNew(
                dedupeKey = "mpesa-callback:${UUID.randomUUID()}", eventType = "stk_callback",
                rawPayload = raw, projectId = payment?.projectId, paymentId = payment?.id,
                paystackReference = reference, provider = "mpesa"
            )
            when {
                reference.isNullOrBlank() || result.ResultCode == null -> eventId?.let { PaymentEventRepository.markFailed(it, "M-Pesa callback is missing its request reference or result code") }
                payment == null -> eventId?.let { PaymentEventRepository.markFailed(it, "No matching M-Pesa payment was found") }
                result.ResultCode == 0 -> {
                    val amount = result.CallbackMetadata?.Item?.firstOrNull { it.Name == "Amount" }?.Value?.toString()?.trim('"')?.toBigDecimalOrNull() ?: payment.amount
                    val project = ProjectRepository.findById(payment.projectId)
                    val applied = project != null && PaymentApplicationService.applySuccessfulPayment(
                        com.gatekeeper.payments.PaymentProvider.MPESA, reference, project.slug, amount, project.currency, "webhook", rawPayload = raw
                    )
                    eventId?.let { if (applied) PaymentEventRepository.markProcessed(it) else PaymentEventRepository.markFailed(it, "M-Pesa payment callback could not be applied") }
                }
                else -> {
                    com.gatekeeper.db.repositories.PaymentRepository.markGatewayStatusByProviderReference(com.gatekeeper.payments.PaymentProvider.MPESA, reference, "failed", "webhook")
                    eventId?.let { PaymentEventRepository.markProcessed(it) }
                }
            }
            call.respond(HttpStatusCode.OK, MpesaCallbackAck())
        }
    }
}
