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
        // Daraja callbacks settle M-Pesa payments. STK queries are reserved for
        // explicit admin checks because the query endpoint is tightly rate limited.
        val pending = payments.pendingPayments(olderThanMinutes).filterNot { it.provider.equals("mpesa", ignoreCase = true) }
        var reconciled = 0
        for (payment in pending) if (reconcile(payment)) reconciled++
        return reconciled
    }

    suspend fun reconcile(payment: Payment): Boolean {
        return runCatching { reconcileStrict(payment) }
            .onFailure { logger.warn("Payment reconciliation failed for paymentId={} provider={} reference={}: {}", payment.id, payment.provider, payment.providerReference, it.message) }
            .getOrDefault(false)
    }

    /** Throws provider verification errors so an explicit admin reconciliation can report them. */
    suspend fun reconcileStrict(payment: Payment): Boolean {
        val gateway = gateways[payment.provider.lowercase()] ?: return false
        val verified = gateway.verify(payment.providerReference).getOrThrow()
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

    private companion object {
        val logger = org.slf4j.LoggerFactory.getLogger(ReconcilePayments::class.java)
    }
}
