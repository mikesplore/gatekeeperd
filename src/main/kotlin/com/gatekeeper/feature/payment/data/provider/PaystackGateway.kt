package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.InitiatedPayment
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import java.math.BigDecimal

class PaystackGateway : PaymentGateway {
    override val provider = "paystack"

    override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> {
        val email = command.email?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("No client email configured for this project"))
        if (AppConfig.paystackSecretKey.isBlank()) return Result.failure(IllegalStateException("Paystack is not configured on this server"))
        val base = AppConfig.publicBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) return Result.failure(IllegalStateException("GATEKEEPER_PUBLIC_URL is not configured"))
        return PaystackClient.initializePaymentWithReference(
            email, command.amount, command.projectSlug, command.currency,
            command.callbackUrl ?: "$base/api/gate/payment/callback?project=${command.projectSlug}", command.serviceId
        ).map { (reference, url) -> InitiatedPayment(reference, url) }
    }

    override suspend fun verify(reference: String): Result<VerifiedPayment> =
        PaystackClient.verifyTransaction(reference).map { data ->
            VerifiedPayment(
                status = data.status.lowercase(),
                amount = data.amount?.let { BigDecimal.valueOf(it).movePointLeft(2) },
                currency = data.currency
            )
        }
}
