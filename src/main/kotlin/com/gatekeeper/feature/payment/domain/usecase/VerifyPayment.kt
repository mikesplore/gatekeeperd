package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import java.util.UUID

class VerifyPayment(
    private val gateways: Map<String, PaymentGateway>,
    private val applyWebhookEvent: ApplyWebhookEvent
) {
    suspend operator fun invoke(command: Command): Result<Verification> = runCatching {
        val gateway = gateways[command.provider.lowercase()] ?: error("Payment provider is unavailable")
        val verified = gateway.verify(command.reference).getOrThrow()
        if (verified.status.equals("success", ignoreCase = true) && verified.amount != null) {
            applyWebhookEvent(
                ApplyWebhookEvent.Command(
                    provider = command.provider,
                    reference = command.reference,
                    status = "success",
                    verifiedVia = command.verifiedVia,
                    amount = verified.amount,
                    currency = verified.currency,
                    paidAt = verified.paidAt,
                    projectId = command.projectId
                )
            )
        }
        Verification(verified, verified.status.equals("success", ignoreCase = true))
    }

    data class Command(val provider: String, val reference: String, val projectId: UUID, val verifiedVia: String)
    data class Verification(val payment: VerifiedPayment, val success: Boolean)
}
