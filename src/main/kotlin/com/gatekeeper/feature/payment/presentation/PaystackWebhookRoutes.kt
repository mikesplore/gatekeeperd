package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.config.AppSettings
import com.gatekeeper.api.respondError
import com.gatekeeper.feature.payment.presentation.dto.PaystackWebhookPayload
import org.koin.ktor.ext.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import com.gatekeeper.plugins.Metrics
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.LocalDateTime
import java.time.OffsetDateTime
import com.gatekeeper.feature.payment.domain.usecase.HandlePaystackWebhook
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent

private val logger = LoggerFactory.getLogger("com.gatekeeper.feature.payment.PaystackWebhookRoutes")
private val json = Json { ignoreUnknownKeys = true }

fun Application.configurePaystackWebhookRoutes() {
    val handlePaystackWebhook = get<HandlePaystackWebhook>()
    val paystackSecret = get<AppSettings>().payment.paystackSecretKey
    routing {
        post("/api/paystack/webhook") {
            Metrics.increment("webhook.received")
            val rawBody = try {
                call.receiveText()
            } catch (e: Exception) {
                logger.error("Failed to read webhook body", e)
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be read")
                return@post
            }

            val signature = call.request.headers["x-paystack-signature"]
            if (signature == null) {
                logger.warn("Webhook missing signature")
                call.respondError(HttpStatusCode.Unauthorized, "missing_signature", "Missing x-paystack-signature header")
                return@post
            }

            if (paystackSecret.isBlank()) {
                logger.error("Webhook received but PAYSTACK_SECRET_KEY is not configured")
                call.respondError(HttpStatusCode.ServiceUnavailable, "paystack_not_configured", "Paystack is not configured")
                return@post
            }

            if (!com.gatekeeper.feature.payment.data.provider.PaystackWebhookSignatureVerifier.verify(rawBody, signature, paystackSecret)) {
                logger.warn("Webhook signature verification failed")
                call.respondError(HttpStatusCode.Unauthorized, "invalid_signature", "Webhook signature verification failed")
                return@post
            }

            val event = try {
                json.decodeFromString<PaystackWebhookPayload>(rawBody)
            } catch (e: Exception) {
                logger.error("Failed to parse webhook JSON", e)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
                return@post
            }

            val data = event.data
            val reference = data.reference
            val projectSlug = data.metadata["project_slug"]
            try {
                val outcome = handlePaystackWebhook(
                    HandlePaystackWebhook.Command(
                        eventType = event.event, paymentStatus = data.status, reference = reference,
                        projectSlug = projectSlug, amountKobo = data.amount,
                        currency = data.currency,
                        paidAt = data.paidAt?.let { value ->
                            runCatching { OffsetDateTime.parse(value).toLocalDateTime() }
                                .recoverCatching { LocalDateTime.parse(value) }
                                .getOrNull()
                        },
                        rawPayload = rawBody
                    )
                )
                when (outcome) {
                    ProcessPaymentEvent.Outcome.DUPLICATE -> Metrics.increment("webhook.duplicate")
                    ProcessPaymentEvent.Outcome.PROCESSED -> Metrics.increment("webhook.processed")
                    ProcessPaymentEvent.Outcome.REJECTED -> Metrics.increment("webhook.failed")
                }
                val responseStatus = when (outcome) {
                    ProcessPaymentEvent.Outcome.PROCESSED -> "ok"
                    ProcessPaymentEvent.Outcome.DUPLICATE -> "duplicate"
                    ProcessPaymentEvent.Outcome.REJECTED -> "rejected"
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to responseStatus))
                return@post
            } catch (e: Exception) {
                Metrics.increment("webhook.failed")
                logger.error("Error processing webhook ${event.event}, ref=$reference", e)
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    "payment_event_processing_failed",
                    "The payment event could not be processed"
                )
                return@post
            }
        }
    }
}
