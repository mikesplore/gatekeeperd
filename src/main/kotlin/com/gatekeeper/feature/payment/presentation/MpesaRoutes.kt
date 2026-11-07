package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.api.InputValidators
import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.HandleMpesaCallback
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import org.koin.ktor.ext.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory



fun Application.configureMpesaRoutes() {
    val handleMpesaCallback = get<HandleMpesaCallback>()
    val callbackToken = get<AppSettings>().payment.mpesaCallbackToken
    val logger = LoggerFactory.getLogger("com.gatekeeper.feature.payment.MpesaCallback")
    routing {
        post("/api/mpesa/pay") {
            val request = runCatching { call.receive<MpesaPaymentRequest>() }.getOrElse {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A valid M-Pesa payment request body is required")
                return@post
            }
            val slug = request.projectSlug.takeIf(String::isNotBlank)?.let { InputValidators.normalizeSlug(it) }
            if (request.projectSlug.isNotBlank() && slug == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_project_slug", "Project slug is invalid")
                return@post
            }
            val phone = request.phoneNumber
            val amountText = request.amount
            val amount = amountText?.toBigDecimalOrNull()
            if (amountText != null && amount == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "M-Pesa payment amount must be a whole KES amount")
                return@post
            }
            val serviceId = runCatching { java.util.UUID.fromString(request.serviceId) }.getOrNull()
            if (serviceId == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "A valid service ID is required")
                return@post
            }
            val initiated = call.application.get<InitiatePayment>()(
                InitiatePayment.Command("mpesa", slug.orEmpty(), amount, phone = phone, currency = "KES", serviceId = serviceId)
            )
            initiated.fold(
                onSuccess = { result -> call.respond(HttpStatusCode.Accepted, mapOf("provider" to "mpesa", "reference" to result.reference, "status" to "pending")) },
                onFailure = { error ->
                    val failure = error as? InitiatePayment.PaymentInitiationFailure
                    val status = when (failure?.kind) {
                        InitiatePayment.FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
                        InitiatePayment.FailureKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
                        InitiatePayment.FailureKind.PROVIDER_UNAVAILABLE, null -> HttpStatusCode.BadGateway
                    }
                    call.respondError(status, failure?.code ?: "mpesa_unavailable", error.message ?: "Unable to initiate M-Pesa payment")
                }
            )
        }

        post("/api/mpesa/callback/{token}") {
            val supplied = call.parameters["token"].orEmpty().toByteArray(Charsets.UTF_8)
            val expected = callbackToken.toByteArray(Charsets.UTF_8)
            if (expected.size < 32 || !java.security.MessageDigest.isEqual(supplied, expected)) {
                call.respondError(HttpStatusCode.NotFound, "not_found", "Callback not found")
                return@post
            }
            val raw = call.receiveText()
            val outcome = handleMpesaCallback(raw)
            logger.info("Safaricom M-Pesa callback received; outcome={}", outcome)
            call.respond(HttpStatusCode.OK, MpesaCallbackAck())
        }
    }
}
