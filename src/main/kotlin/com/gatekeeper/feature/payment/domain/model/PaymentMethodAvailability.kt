package com.gatekeeper.feature.payment.domain.model

data class PaymentMethodAvailability(val paystack: Boolean, val mpesa: Boolean)
