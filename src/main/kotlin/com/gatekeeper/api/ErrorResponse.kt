package com.gatekeeper.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val data: JsonElement? = null
)

/** An expected HTTP failure that StatusPages converts to the API error envelope. */
class HttpApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String
) : RuntimeException(message)

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
    message = normalizeErrorMessage(message),
    timestamp = currentErrorTimestamp()
)

/** Remove Docker client's HTTP/JSON wrapper from messages returned to API clients. */
internal fun normalizeErrorMessage(message: String): String {
    var normalized = message.trim()
    repeat(2) {
        val statusPrefix = Regex("^Status\\s+\\d{3}:\\s*").find(normalized)
        if (statusPrefix != null) normalized = normalized.substring(statusPrefix.range.last + 1).trim()
        val nestedMessage = runCatching {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(normalized)
            (json as? JsonObject)?.get("message")?.let { value ->
                (value as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()
        if (nestedMessage != null) normalized = nestedMessage.trim()
    }

    return when {
        Regex("(?i)^remove\\s+.*?:\\s*volume is in use").containsMatchIn(normalized) ->
            "This volume is still attached to one or more containers. Stop or remove those containers before deleting it."
        Regex("(?i)^remove\\s+.*?:\\s*network .*active endpoints").containsMatchIn(normalized) ->
            "This network is still in use by one or more containers. Disconnect them before deleting it."
        else -> normalized.ifBlank { message }
    }
}

fun paymentRequiredResponse(
    paywall: com.gatekeeper.gate.PaywallInfo?,
    supportContactEmail: String
): PaymentRequiredResponse {
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
        pay_url = paywall?.let { "/api/customer/projects/${java.net.URLEncoder.encode(it.slug, Charsets.UTF_8)}/portal" }.orEmpty(),
        project = paywall?.name.orEmpty(),
        domain = paywall?.domain.orEmpty(),
        amount_due = paywall?.amountDue?.stripTrailingZeros()?.toPlainString().orEmpty(),
        currency = paywall?.currency.orEmpty(),
        due_date = paywall?.dueDate?.format(dateFormatter).orEmpty(),
        contact = supportContactEmail,
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
    respond(status, ErrorResponse(error, normalizeErrorMessage(message), currentErrorTimestamp(), data))
}
