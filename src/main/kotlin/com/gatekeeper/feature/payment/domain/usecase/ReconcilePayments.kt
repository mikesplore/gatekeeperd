package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository


class ReconcilePayments(
    private val payments: PaymentRepository,
    private val gateways: Map<String, PaymentGateway>,
    private val applyWebhookEvent: ApplyWebhookEvent
) {
    suspend operator fun invoke(olderThanMinutes: Long): Int {
        val pending = payments.pendingPayments(olderThanMinutes)
        var reconciled = 0
        for (payment in pending) if (reconcile(payment)) reconciled++
        return reconciled
    }

    suspend fun reconcile(payment: Payment): Boolean {
        val gateway = gateways[payment.provider.lowercase()] ?: return false
        val verified = gateway.verify(payment.providerReference).getOrNull() ?: return false
        return verified.status != payment.status && applyWebhookEvent(
            ApplyWebhookEvent.Command(
                provider = payment.provider,
                reference = payment.providerReference,
                status = verified.status,
                verifiedVia = "reconciliation",
                amount = verified.amount,
                currency = verified.currency,
                paidAt = verified.paidAt
            )
        )
    }

    suspend fun reconcile(provider: String, reference: String): Boolean {
        val payment = payments.findByProviderReference(provider, reference) ?: return false
        return reconcile(payment)
    }
}
