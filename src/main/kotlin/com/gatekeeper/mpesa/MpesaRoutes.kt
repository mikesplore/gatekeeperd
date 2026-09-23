package com.gatekeeper.mpesa

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.payments.ProjectBalanceService
import com.gatekeeper.payments.PaymentApplicationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.math.BigDecimal

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
            if (amountText != null && (amount == null || amount <= BigDecimal.ZERO || amount.scale().coerceAtLeast(0) > 2)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be greater than zero and have at most two decimal places")
                return@post
            }
            val project = ProjectRepository.findBySlug(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@post
            }
            if (!project.currency.equals("KES", ignoreCase = true)) {
                call.respondError(HttpStatusCode.BadRequest, "unsupported_payment_currency", "M-Pesa payments are only supported in KES")
                return@post
            }
            if (amount != null && amount.stripTrailingZeros().scale() > 0) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "M-Pesa payment amount must be a whole KES amount")
                return@post
            }
            if (runCatching { ProjectBalanceService.requireAvailableForNewPayment(project, amount) }.isFailure) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount exceeds the available outstanding balance")
                return@post
            }
            MpesaClient.initiate(project, phone, amount).fold(
                onSuccess = { reference -> call.respond(HttpStatusCode.Accepted, mapOf("provider" to "mpesa", "reference" to reference, "status" to "pending")) },
                onFailure = { call.respondError(HttpStatusCode.BadGateway, "mpesa_unavailable", it.message ?: "Unable to initiate M-Pesa payment") }
            )
        }

        post("/api/mpesa/callback") {
            val raw = call.receiveText()
            val callback = runCatching { json.decodeFromString<MpesaCallback>(raw) }.getOrNull()
            val result = callback?.Body?.stkCallback
            if (result == null) {
                call.respond(HttpStatusCode.OK, mapOf("ResultCode" to 0, "ResultDesc" to "Accepted"))
                return@post
            }
            val reference = result.CheckoutRequestID
            val payment = reference?.let { com.gatekeeper.db.repositories.PaymentRepository.findByProviderReference(com.gatekeeper.payments.PaymentProvider.MPESA, it) }
            if (payment != null && result.ResultCode == 0) {
                val amount = result.CallbackMetadata?.Item?.firstOrNull { it.Name == "Amount" }?.Value?.toString()?.trim('"')?.toBigDecimalOrNull() ?: payment.amount
                val project = ProjectRepository.findById(payment.projectId)
                if (project != null) PaymentApplicationService.applySuccessfulPayment(com.gatekeeper.payments.PaymentProvider.MPESA, reference, project.slug, amount, project.currency, "webhook", rawPayload = raw)
            } else if (payment != null && result.ResultCode != 0) {
                com.gatekeeper.db.repositories.PaymentRepository.markGatewayStatusByProviderReference(com.gatekeeper.payments.PaymentProvider.MPESA, reference!!, "failed", "webhook")
            }
            call.respond(HttpStatusCode.OK, mapOf("ResultCode" to 0, "ResultDesc" to "Accepted"))
        }
    }
}
