package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness

class PaymentProviderReadinessAdapter(
    private val paymentSettings: AppSettings.PaymentSettings,
    private val mpesaGateway: MpesaGateway
) : PaymentProviderReadiness {
    override fun isConfigured(provider: String): Boolean = when (provider.lowercase()) {
        "paystack" -> paymentSettings.paystackSecretKey.isNotBlank()
        "mpesa" -> mpesaGateway.isConfigured()
        else -> false
    }
}
