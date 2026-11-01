package com.gatekeeper.customer

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class CustomerPaymentResponse(
    val id: String,
    val reference: String,
    val amount: Double,
    val currency: String,
    val status: String,
    val paidAt: String? = null,
    val receiptUrl: String
)

@Serializable
data class CustomerReceiptResponse(
    val receiptNumber: String,
    val project: String,
    val domain: String,
    val amount: Double,
    val currency: String,
    val paidAt: String? = null,
    val reference: String
)

@Serializable
data class CustomerStatusResponse(
    val project: String,
    val name: String,
    val domain: String,
    val accessStatus: String,
    val blockReason: String? = null,
    val amountDue: Double? = null,
    val currency: String,
    val dueDate: String? = null,
    val paymentUrl: String,
    val portalUrl: String,
    val supportUrl: String,
    val payments: List<CustomerPaymentResponse>
)

@Serializable
data class SupportRequestBody(
    val name: String? = null,
    val email: String,
    val message: String
)

@Serializable
data class SupportRequestResponse(val id: String, val status: String, val message: String)

@Serializable
data class CustomerReminderResponse(
    val recipient: String,
    val subject: String,
    val message: String,
    val paymentUrl: String,
    val frontendUrl: String? = null
)
