package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.api.InputValidators
import com.gatekeeper.feature.payment.domain.usecase.HandleMpesaCallback
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import org.koin.ktor.ext.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class MpesaPaymentRequest(
    val projectSlug: String = "",
    val serviceId: String,
    val phoneNumber: String,
    val amount: String? = null
)

@Serializable
data class MpesaCallbackAck(val ResultCode: Int = 0, val ResultDesc: String = "Accepted")

fun Application.configureMpesaRoutes() {
    val handleMpesaCallback = get<HandleMpesaCallback>()
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

        post("/api/mpesa/callback") {
            val raw = call.receiveText()
            handleMpesaCallback(raw)
            call.respond(HttpStatusCode.OK, MpesaCallbackAck())
        }
    }
}
