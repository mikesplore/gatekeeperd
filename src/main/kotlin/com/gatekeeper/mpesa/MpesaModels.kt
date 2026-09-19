package com.gatekeeper.mpesa

import kotlinx.serialization.Serializable

@Serializable data class MpesaTokenResponse(val access_token: String, val expires_in: String)
@Serializable data class MpesaStkRequest(
    val BusinessShortCode: String, val Password: String, val Timestamp: String,
    val TransactionType: String = "CustomerPayBillOnline", val Amount: Long,
    val PartyA: String, val PartyB: String, val PhoneNumber: String,
    val CallBackURL: String, val AccountReference: String, val TransactionDesc: String
)
@Serializable data class MpesaStkResponse(
    val MerchantRequestID: String? = null, val CheckoutRequestID: String? = null,
    val ResponseCode: String? = null, val ResponseDescription: String? = null,
    val CustomerMessage: String? = null
)
@Serializable data class MpesaCallback(val Body: MpesaCallbackBody? = null)
@Serializable data class MpesaCallbackBody(val stkCallback: MpesaStkCallback? = null)
@Serializable data class MpesaStkCallback(
    val MerchantRequestID: String? = null, val CheckoutRequestID: String? = null,
    val ResultCode: Int? = null, val ResultDesc: String? = null,
    val CallbackMetadata: MpesaCallbackMetadata? = null
)
@Serializable data class MpesaCallbackMetadata(val Item: List<MpesaCallbackItem> = emptyList())
@Serializable data class MpesaCallbackItem(val Name: String, val Value: kotlinx.serialization.json.JsonElement? = null)
