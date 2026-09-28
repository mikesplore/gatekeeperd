package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness
import com.gatekeeper.mpesa.MpesaClient

class LegacyPaymentProviderReadiness : PaymentProviderReadiness {
    override fun isConfigured(provider: String): Boolean = when (provider.lowercase()) {
        "paystack" -> AppConfig.paystackSecretKey.isNotBlank()
        "mpesa" -> MpesaClient.isConfigured()
        else -> false
    }
}
