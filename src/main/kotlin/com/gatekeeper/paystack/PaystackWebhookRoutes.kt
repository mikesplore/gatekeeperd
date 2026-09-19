package com.gatekeeper.paystack

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentEventRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import com.gatekeeper.plugins.Metrics
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = LoggerFactory.getLogger("com.gatekeeper.paystack.PaystackWebhookRoutes")
private val json = Json { ignoreUnknownKeys = true }

fun Application.configurePaystackWebhookRoutes() {
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

            if (AppConfig.paystackSecretKey.isBlank()) {
                logger.error("Webhook received but PAYSTACK_SECRET_KEY is not configured")
                call.respondError(HttpStatusCode.ServiceUnavailable, "paystack_not_configured", "Paystack is not configured")
                return@post
            }

            if (!verifySignature(rawBody, signature, AppConfig.paystackSecretKey)) {
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
            val dedupeKey = "${event.event}:$reference"

            if (PaymentEventRepository.alreadyRecorded(dedupeKey)) {
                logger.info("Ignoring duplicate Paystack webhook event=$dedupeKey")
                call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))
                return@post
            }

            val projectSlug = data.metadata["project_slug"]
            val projectId = resolveProjectId(projectSlug, reference)
            val paymentId = PaymentRepository.findByReference(reference)?.id

            val eventId = PaymentEventRepository.recordIfNew(
                dedupeKey = dedupeKey,
                eventType = event.event,
                rawPayload = rawBody,
                projectId = projectId,
                paymentId = paymentId,
                paystackReference = reference
            )
            if (eventId == null) {
                Metrics.increment("webhook.duplicate")
                logger.info("Ignoring duplicate Paystack webhook event=$dedupeKey")
                call.respond(HttpStatusCode.OK, mapOf("status" to "duplicate"))
                return@post
            }

            try {
                when (event.event) {
                    "charge.success" -> {
                        if (!data.status.equals("success", ignoreCase = true)) {
                            logger.info("Ignoring charge.success with non-success data.status=${data.status}, ref=$reference")
                        } else if (projectSlug.isNullOrBlank()) {
                            logger.warn("charge.success missing project_slug, ref=$reference")
                        } else {
                            val applied = PaymentService.applySuccessfulPayment(
                                reference = reference,
                                projectSlug = projectSlug,
                                amountNaira = koboToNaira(data.amount),
                                currency = data.currency,
                                verifiedVia = "webhook",
                                rawPayload = rawBody
                            )
                            if (!applied) {
                                PaymentEventRepository.markFailed(eventId, "Payment integrity checks failed")
                                call.respond(HttpStatusCode.OK, mapOf("status" to "rejected"))
                                return@post
                            }
                        }
                    }
                    "charge.failed" -> {
                        PaymentService.handleChargeFailed(reference, projectSlug, rawBody)
                    }
                    "transfer.reversed", "charge.reversed" -> {
                        PaymentService.handleReversal(reference)
                    }
                    else -> {
                        logger.info("Webhook event recorded, no handler: ${event.event}, ref=$reference")
                    }
                }
                PaymentEventRepository.markProcessed(eventId)
                Metrics.increment("webhook.processed")
            } catch (e: Exception) {
                Metrics.increment("webhook.failed")
                logger.error("Error processing webhook ${event.event}, ref=$reference", e)
                PaymentEventRepository.markFailed(eventId, e.message ?: "Webhook processing failed")
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

private fun resolveProjectId(projectSlug: String?, reference: String): java.util.UUID? {
    projectSlug?.let { slug ->
        ProjectRepository.findBySlug(slug)?.id?.let { return it }
    }
    return PaymentRepository.findByReference(reference)?.projectId
}

private fun verifySignature(rawBody: String, signature: String, secretKey: String): Boolean {
    return try {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA512"))
        val expected = bytesToHex(mac.doFinal(rawBody.toByteArray()))
        constantTimeEquals(expected, signature)
    } catch (e: Exception) {
        logger.error("Signature verification error", e)
        false
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}

private fun koboToNaira(amountKobo: Long): BigDecimal =
    BigDecimal.valueOf(amountKobo).movePointLeft(2)

private fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it) }
}
