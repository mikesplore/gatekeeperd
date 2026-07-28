package com.gatekeeper.paystack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaystackInitializeRequest(
    val email: String,
    val amount: Long, // in kobo
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class PaystackInitializeResponse(
    val status: Boolean,
    val message: String,
    val data: PaystackInitializeData?
)

@Serializable
data class PaystackInitializeData(
    val reference: String,
    val amount: Long,
    val currency: String,
    val authorization_url: String
)

@Serializable
data class PaystackWebhookPayload(
    val event: String,
    val data: PaystackWebhookData
)

@Serializable
data class PaystackWebhookData(
    val reference: String,
    val amount: Long,
    val status: String,
    val metadata: Map<String, String> = emptyMap(),
    val customer: PaystackCustomer? = null
)

@Serializable
data class PaystackCustomer(
    val email: String
)