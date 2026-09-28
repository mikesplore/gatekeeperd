package com.gatekeeper.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)

@Serializable
data class ErrorResponseWithData(
    val error: String,
    val message: String,
    val timestamp: String,
    val data: JsonElement
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
    val contact: String = "support@gatekeeper.local",
    val block_reason: String = "",
    val reason_source: String = "",
    val reason_note: String? = null,
    val service: String = ""
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
        message = when (paywall?.blockReasonCode) {
            com.gatekeeper.db.tables.AccessBlockReason.PAYMENT -> "Access is suspended pending payment."
            com.gatekeeper.db.tables.AccessBlockReason.MANUAL_HOLD -> "Access has been temporarily paused."
            com.gatekeeper.db.tables.AccessBlockReason.ABUSE_TOS -> "Access has been restricted."
            com.gatekeeper.db.tables.AccessBlockReason.SUSPENDED_BY_REQUEST -> "This service has been suspended by request."
            com.gatekeeper.db.tables.AccessBlockReason.OTHER -> "Access is currently unavailable."
            null -> "Access is currently unavailable."
        },
        timestamp = currentErrorTimestamp(),
        pay_url = paywall?.let { "/api/gate/pay?project=${it.slug}" }.orEmpty(),
        project = paywall?.name.orEmpty(),
        domain = paywall?.domain.orEmpty(),
        amount_due = paywall?.amountDue?.stripTrailingZeros()?.toPlainString().orEmpty(),
        currency = paywall?.currency.orEmpty(),
        due_date = paywall?.dueDate?.format(dateFormatter).orEmpty(),
        contact = com.gatekeeper.config.AppConfig.supportContactEmail,
        block_reason = paywall?.blockReasonCode?.value.orEmpty(),
        reason_source = paywall?.reasonSource.orEmpty(),
        reason_note = paywall?.blockReasonNote,
        service = paywall?.serviceName.orEmpty()
    )
}

suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String, message: String) {
    respond(status, errorResponse(error, message))
}

suspend fun ApplicationCall.respondErrorWithData(
    status: HttpStatusCode,
    error: String,
    message: String,
    data: JsonElement
) {
    respond(
        status,
        ErrorResponseWithData(
            error = error,
            message = message,
            timestamp = currentErrorTimestamp(),
            data = data
        )
    )
}
