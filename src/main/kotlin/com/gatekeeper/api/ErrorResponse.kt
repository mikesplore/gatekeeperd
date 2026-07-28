package com.gatekeeper.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)

@Serializable
data class PaymentRequiredResponse(
    val error: String = "payment_required",
    val message: String,
    val timestamp: String,
    val payment_link: String = "",
    val pay_url: String = "",
    val project: String = "",
    val domain: String = "",
    val amount_due: String = "",
    val currency: String = "",
    val due_date: String = "",
    val contact: String = "support@gatekeeper.local"
)

private val timestampFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun currentErrorTimestamp(): String = LocalDateTime.now().format(timestampFormatter)

fun errorResponse(error: String, message: String): ErrorResponse = ErrorResponse(
    error = error,
    message = message,
    timestamp = currentErrorTimestamp()
)

fun paymentRequiredResponse(paywall: com.gatekeeper.gate.PaywallInfo?): PaymentRequiredResponse {
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    return PaymentRequiredResponse(
        message = "Access to this API is suspended pending payment.",
        timestamp = currentErrorTimestamp(),
        pay_url = paywall?.let { "/api/gate/pay?project=${it.slug}" }.orEmpty(),
        project = paywall?.name.orEmpty(),
        domain = paywall?.domain.orEmpty(),
        amount_due = paywall?.amountDue?.stripTrailingZeros()?.toPlainString().orEmpty(),
        currency = paywall?.currency.orEmpty(),
        due_date = paywall?.dueDate?.format(dateFormatter).orEmpty(),
        contact = com.gatekeeper.config.AppConfig.supportContactEmail
    )
}

suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String, message: String) {
    respond(status, errorResponse(error, message))
}
