package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class CaptureCashPaymentRequest(
    val amount: Double,
    val currency: String? = null,
    val paidAt: String? = null,
    val receiptNumber: String? = null,
    val notes: String? = null,
)

@Serializable
data class InitializePaymentRequest(val email: String, val amount: Double? = null)

@Serializable
data class InitializePaymentResponse(val payment_link: String)

@Serializable
data class ProjectPaymentsPageResponse(val payments: List<PaymentResponse>, val total: Long, val limit: Int, val offset: Int, val hasMore: Boolean)

@Serializable
data class ReconcilePaymentResponse(val id: String, val reconciled: Boolean, val gatewayStatus: String?)
