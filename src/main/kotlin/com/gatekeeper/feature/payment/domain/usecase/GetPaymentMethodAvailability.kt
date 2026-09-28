package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.PaymentMethodAvailability

class GetPaymentMethodAvailability(private val providers: PaymentProviderReadiness) {
    operator fun invoke(currency: String) = PaymentMethodAvailability(
        paystack = providers.isConfigured("paystack"),
        mpesa = currency.equals("KES", ignoreCase = true) && providers.isConfigured("mpesa")
    )
}

interface PaymentProviderReadiness {
    fun isConfigured(provider: String): Boolean
}
