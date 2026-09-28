package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackData
import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

class JsonMpesaCallbackDecoder : MpesaCallbackDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    override fun decode(rawPayload: String): MpesaCallbackData? = runCatching {
        val result = json.decodeFromString<MpesaCallback>(rawPayload).Body?.stkCallback ?: return null
        val amount = result.CallbackMetadata?.Item?.firstOrNull { it.Name == "Amount" }
            ?.Value?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
        MpesaCallbackData(result.CheckoutRequestID, result.ResultCode, amount)
    }.getOrNull()
}
