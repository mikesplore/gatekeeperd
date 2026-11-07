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

@Serializable
data class CustomerAccountResponse(
    val name: String,
    val email: String,
    val services: List<CustomerAccountService>,
    val payments: List<CustomerAccountPayment>
)

@Serializable
data class CustomerAccountService(
    val project: String,
    val projectName: String,
    val serviceId: String,
    val serviceName: String,
    val domain: String,
    val accessStatus: String,
    val invoiceAmount: String?,
    val paid: String?,
    val balance: String?,
    val currency: String,
    val dueDate: String?,
    val invoiceUrl: String?,
    val paymentUrl: String?
)

@Serializable
data class CustomerAccountPayment(
    val id: String,
    val serviceName: String,
    val amount: String,
    val currency: String,
    val status: String,
    val createdAt: String?,
    val paidAt: String?,
    val receiptUrl: String?
)

@Serializable data class CustomerAccessRequest(val email: String)
@Serializable data class CustomerAccessChallenge(val challenge: String, val message: String)
@Serializable data class CustomerAccessVerify(val challenge: String, val code: String)
@Serializable data class CustomerAccessSession(val authenticated: Boolean)
