package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackData
import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackDecoder
import kotlinx.serialization.json.Json

class JsonMpesaCallbackDecoder : MpesaCallbackDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    override fun decode(rawPayload: String): MpesaCallbackData? = runCatching {
        val result = json.decodeFromString<MpesaCallback>(rawPayload).Body?.stkCallback ?: return null
        MpesaCallbackData(result.CheckoutRequestID, result.ResultCode)
    }.getOrNull()
}
