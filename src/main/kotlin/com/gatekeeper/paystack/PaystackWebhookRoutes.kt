package com.gatekeeper.paystack

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = LoggerFactory.getLogger("com.gatekeeper.paystack.PaystackWebhookRoutes")
private val json = Json { ignoreUnknownKeys = true }

fun Application.configurePaystackWebhookRoutes() {
    routing {
        post("/api/paystack/webhook") {
            val rawBody = try {
                call.receiveText()
            } catch (e: Exception) {
                logger.error("Failed to read webhook body", e)
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                return@post
            }

            val signature = call.request.headers["x-paystack-signature"]
            if (signature == null) {
                logger.warn("Webhook missing signature")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_signature"))
                return@post
            }

            if (!verifySignature(rawBody, signature, AppConfig.paystackSecretKey)) {
                logger.warn("Webhook signature verification failed")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_signature"))
                return@post
            }

            val event = try {
                json.decodeFromString<PaystackWebhookPayload>(rawBody)
            } catch (e: Exception) {
                logger.error("Failed to parse webhook JSON", e)
                call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
                return@post
            }

            if (event.event != "charge.success") {
                return@post call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
            }

            val data = event.data
            val reference = data.reference
            val projectSlug = data.metadata["project_slug"]

            if (projectSlug.isNullOrBlank()) {
                logger.warn("Webhook missing project_slug in metadata, ref=$reference")
                return@post call.respond(HttpStatusCode.OK, mapOf("status" to "ignored"))
            }

            try {
                // Idempotency: find by reference first
                val existing = PaymentRepository.findByReference(reference)
                if (existing != null && existing.status == "success") {
                    logger.info("Webhook already processed (idempotent), ref=$reference")
                    return@post call.respond(HttpStatusCode.OK, mapOf("status" to "already_processed"))
                }

                val project = ProjectRepository.findBySlug(projectSlug)
                if (project == null) {
                    logger.warn("Webhook project not found: $projectSlug")
                    return@post call.respond(HttpStatusCode.OK, mapOf("status" to "project_not_found"))
                }

                val paidAt = LocalDateTime.now()

                if (existing == null) {
                    PaymentRepository.create(
                        projectId = project.id,
                        paystackReference = reference,
                        amount = BigDecimal.valueOf(data.amount),
                        status = "success",
                        rawWebhookPayload = rawBody
                    )
                } else {
                    PaymentRepository.markSuccess(reference, paidAt)
                }

                ProjectRepository.updateStatus(project.id, "active", "system", "payment_received via webhook")
                ProjectRepository.invalidateCache(projectSlug)

                logger.info("Payment received and project activated: $projectSlug, ref=$reference")
            } catch (e: Exception) {
                logger.error("Error processing webhook charge.success, ref=$reference", e)
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

private fun verifySignature(rawBody: String, signature: String, secretKey: String): Boolean {
    return try {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA512"))
        val expected = bytesToHex(mac.doFinal(rawBody.toByteArray()))
        expected == signature
    } catch (e: Exception) {
        logger.error("Signature verification error", e)
        false
    }
}

private fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it) }
}