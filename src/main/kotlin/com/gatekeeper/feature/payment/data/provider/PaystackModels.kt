package com.gatekeeper.feature.payment.data.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PaystackInitializeRequest(
    val email: String,
    val amount: Long, // in kobo
    val currency: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("callback_url") val callbackUrl: String? = null
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
    val authorization_url: String,
    val amount: Long? = null,
    val currency: String? = null
)

@Serializable
data class PaystackVerifyResponse(
    val status: Boolean,
    val message: String = "",
    val data: PaystackVerifyData? = null
)

@Serializable
data class PaystackVerifyData(
    val status: String,
    val reference: String,
    val amount: Long? = null,
    val currency: String? = null
)
