package com.gatekeeper.feature.payment.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class MpesaPaymentRequest(
    val projectSlug: String = "",
    val serviceId: String,
    val phoneNumber: String,
    val amount: String? = null
)

@Serializable
data class MpesaCallbackAck(val ResultCode: Int = 0, val ResultDesc: String = "Accepted")
