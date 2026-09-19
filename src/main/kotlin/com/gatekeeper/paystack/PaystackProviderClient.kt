package com.gatekeeper.paystack

import com.gatekeeper.payments.PaymentProvider
import com.gatekeeper.payments.PaymentProviderClient
import com.gatekeeper.payments.VerifiedPayment
import com.gatekeeper.payments.VerifiedPaymentStatus
import java.math.BigDecimal

class PaystackProviderClient : PaymentProviderClient {
    override val provider = PaymentProvider.PAYSTACK
    override suspend fun verify(reference: String): Result<VerifiedPayment> =
        PaystackClient.verifyTransaction(reference).map { data ->
            VerifiedPayment(provider, data.reference, when (data.status.lowercase()) {
                "success" -> VerifiedPaymentStatus.SUCCESS
                "failed" -> VerifiedPaymentStatus.FAILED
                "abandoned" -> VerifiedPaymentStatus.ABANDONED
                "reversed" -> VerifiedPaymentStatus.REVERSED
                else -> VerifiedPaymentStatus.PENDING
            }, data.amount?.let { BigDecimal.valueOf(it).movePointLeft(2) }, data.currency)
        }
}
