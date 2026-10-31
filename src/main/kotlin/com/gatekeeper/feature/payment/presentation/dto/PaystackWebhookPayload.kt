package com.gatekeeper.feature.payment.presentation.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class PaystackWebhookPayload(val event: String, val data: PaystackWebhookData)

@Serializable
data class PaystackWebhookData(
    val reference: String,
    val amount: Long,
    val status: String,
    val currency: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val customer: PaystackCustomer? = null
)

@Serializable
data class PaystackCustomer(val email: String)
