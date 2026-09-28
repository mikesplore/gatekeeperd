package com.gatekeeper.paystack

import com.gatekeeper.config.AppConfig

object ProjectPaymentService {

    fun isPaystackConfigured(): Boolean = AppConfig.paystackSecretKey.isNotBlank()

}
