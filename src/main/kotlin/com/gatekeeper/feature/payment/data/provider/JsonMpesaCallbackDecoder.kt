package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackData
import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackDecoder
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class JsonMpesaCallbackDecoder : MpesaCallbackDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    override fun decode(rawPayload: String): MpesaCallbackData? = runCatching {
        val result = json.decodeFromString<MpesaCallback>(rawPayload).Body?.stkCallback ?: return null
        val metadata = result.CallbackMetadata?.Item.orEmpty().associate { item ->
            item.Name to item.Value?.toString()?.trim('"')
        }
        val paidAt = metadata["TransactionDate"]?.let { value ->
            runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) }.getOrNull()
        }
        MpesaCallbackData(
            reference = result.CheckoutRequestID,
            resultCode = result.ResultCode,
            amount = metadata["Amount"]?.let { runCatching { BigDecimal(it) }.getOrNull() },
            receiptNumber = metadata["MpesaReceiptNumber"],
            paidAt = paidAt
        )
    }.getOrNull()
}
