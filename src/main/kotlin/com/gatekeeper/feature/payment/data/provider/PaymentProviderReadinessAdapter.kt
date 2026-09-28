package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness

class PaymentProviderReadinessAdapter : PaymentProviderReadiness {
    override fun isConfigured(provider: String): Boolean = when (provider.lowercase()) {
        "paystack" -> AppConfig.paystackSecretKey.isNotBlank()
        "mpesa" -> MpesaGateway.isConfigured()
        else -> false
    }
}
