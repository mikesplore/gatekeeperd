package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.feature.payment.domain.usecase.CompletePaystackCallback
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.feature.payment.PaymentRoutes")

fun Application.configurePaymentRoutes() {
    routing {
        get("/api/gate/pay") {
            val rawSlug = call.request.queryParameters["project"]
            val slug = rawSlug?.takeIf(String::isNotBlank)?.let { InputValidators.normalizeSlug(it) }
            if (!rawSlug.isNullOrBlank() && slug == null) {
                return@get call.respondError(HttpStatusCode.BadRequest, "invalid_project_slug", "Project slug is invalid")
            }
            val amountText = call.request.queryParameters["amount"]
            val amount = amountText?.toBigDecimalOrNull()
            if (amountText != null && amount == null) {
                return@get call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be a valid number")
            }
            val serviceIdText = call.request.queryParameters["serviceId"]
            val serviceId = serviceIdText?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            if (serviceIdText != null && serviceId == null) {
                return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "A valid service ID is required")
            }
            if (serviceId == null) {
                return@get call.respondError(HttpStatusCode.BadRequest, "service_required", "A service is required for payment")
            }
            call.application.get<InitiatePayment>()(
                InitiatePayment.Command("paystack", slug.orEmpty(), amount, currency = "", serviceId = serviceId)
            ).fold(
                onSuccess = { initiated ->
                    call.respondRedirect(
                        initiated.authorizationUrl ?: "/api/gate/payment/callback?project=${initiated.projectSlug}&reference=${initiated.reference}",
                        permanent = false
                    )
                },
                onFailure = { error ->
                    val failure = error as? InitiatePayment.PaymentInitiationFailure
                    val status = when (failure?.kind) {
                        InitiatePayment.FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
                        InitiatePayment.FailureKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.BadGateway
                    }
                    call.respondError(status, failure?.code ?: "payment_unavailable", error.message ?: "Unable to start payment")
                }
            )
        }

        get("/api/gate/payment/callback") {
            val slug = call.request.queryParameters["project"].orEmpty()
            val reference = call.request.queryParameters["reference"] ?: call.request.queryParameters["trxref"].orEmpty()
            if (slug.isBlank() || reference.isBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, "invalid_callback", "Missing project or payment reference")
            }
            call.application.get<CompletePaystackCallback>()(slug, reference).fold(
                onSuccess = { completion ->
                    if (completion.successful) logger.info("Payment callback verified and applied for ${completion.projectSlug}, ref=$reference")
                    else logger.warn("Payment callback could not be verified for ${completion.projectSlug}, ref=$reference")
                    call.respondRedirect(projectRedirectUrl(completion.projectDomain), permanent = false)
                },
                onFailure = { error ->
                    val failure = error as? CompletePaystackCallback.CallbackFailure
                    val status = when (failure?.kind) {
                        CompletePaystackCallback.FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
                        CompletePaystackCallback.FailureKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.BadGateway
                    }
                    call.respondError(status, failure?.code ?: "payment_verification_failed", error.message ?: "Unable to verify payment")
                }
            )
        }
    }
}
