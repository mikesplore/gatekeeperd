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
    val payment_link: String,
    val contact: String = "support@gatekeeper.local"
)

private val timestampFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun currentErrorTimestamp(): String = LocalDateTime.now().format(timestampFormatter)

fun errorResponse(error: String, message: String): ErrorResponse = ErrorResponse(
    error = error,
    message = message,
    timestamp = currentErrorTimestamp()
)

fun paymentRequiredResponse(paymentLink: String?): PaymentRequiredResponse = PaymentRequiredResponse(
    message = "Access to this API is suspended pending payment.",
    timestamp = currentErrorTimestamp(),
    payment_link = paymentLink.orEmpty()
)

suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String, message: String) {
    respond(status, errorResponse(error, message))
}
