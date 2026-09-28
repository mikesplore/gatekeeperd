package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.InitiatedPayment
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import com.gatekeeper.payments.PaymentProviderClient
import com.gatekeeper.paystack.PaystackClient
import com.gatekeeper.config.AppConfig
import com.gatekeeper.mpesa.MpesaClient

class LegacyPaymentGatewayAdapter(private val client: PaymentProviderClient) : PaymentGateway {
    override val provider: String = client.provider.name.lowercase()

    override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> = when (provider) {
        "paystack" -> {
            val email = command.email?.takeIf { it.isNotBlank() }
                ?: return Result.failure(IllegalStateException("No client email configured for this project"))
            if (AppConfig.paystackSecretKey.isBlank()) return Result.failure(IllegalStateException("Paystack is not configured on this server"))
            val base = AppConfig.publicBaseUrl.trim().trimEnd('/')
            if (base.isBlank()) return Result.failure(IllegalStateException("GATEKEEPER_PUBLIC_URL is not configured"))
            PaystackClient.initializePaymentWithReference(email, command.amount, command.projectSlug, command.currency,
                command.callbackUrl ?: "$base/api/gate/payment/callback?project=${command.projectSlug}")
                .map { (reference, url) -> InitiatedPayment(reference, url) }
        }
        "mpesa" -> MpesaClient.initiate(command)
        else -> Result.failure(UnsupportedOperationException("Payment initiation is not supported for $provider"))
    }

    override suspend fun verify(reference: String): Result<VerifiedPayment> = client.verify(reference).map {
        VerifiedPayment(
            status = it.status.name.lowercase(),
            amount = it.amount,
            currency = it.currency,
            paidAt = it.paidAt
        )
    }
}
