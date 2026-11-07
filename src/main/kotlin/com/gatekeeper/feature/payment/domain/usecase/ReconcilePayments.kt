package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import kotlinx.coroutines.delay


class ReconcilePayments(
    private val payments: PaymentRepository,
    private val gateways: Map<String, PaymentGateway>,
    private val applyWebhookEvent: ApplyWebhookEvent,
    private val callbackRetryDelayMs: Long = CALLBACK_RETRY_DELAY_MS
) {
    suspend operator fun invoke(olderThanMinutes: Long): Int {
        val pending = payments.pendingPayments(olderThanMinutes)
        var reconciled = 0
        for (payment in pending) if (reconcile(payment)) reconciled++
        return reconciled
    }

    /** Reconcile a small oldest-first M-Pesa batch frequently to recover dropped callbacks. */
    suspend fun reconcilePendingMpesaBatch(limit: Int = 10): Int {
        var reconciled = 0
        for (payment in payments.pendingMpesaPayments(limit)) {
            if (reconcile(payment)) reconciled++
            // Rotate unresolved payments through bounded batches instead of
            // letting the oldest still-pending checkout starve newer ones.
            if (payments.findById(payment.id)?.status == "pending") {
                payments.recordVerificationAttempt(payment.provider, payment.providerReference)
            }
        }
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

    /**
     * A successful STK callback can reach Gatekeeperd before Daraja's query API
     * has published the final result. Retry that short propagation window here;
     * callback contents still never settle the payment on their own.
     */
    suspend fun reconcileMpesaCallback(reference: String, callbackResultCode: Int): Boolean {
        val payment = payments.findByProviderReference("mpesa", reference) ?: run {
            logger.warn("M-Pesa callback has no matching payment yet; reference={}", reference)
            return false
        }
        val gateway = gateways["mpesa"] ?: return false
        val attempts = if (callbackResultCode == 0) CALLBACK_SUCCESS_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            val verified = gateway.verify(payment.providerReference).getOrElse { error ->
                logger.warn("M-Pesa callback verification attempt {}/{} failed for paymentId={}: {}", attempt + 1, attempts, payment.id, error.message)
                if (attempt + 1 < attempts) delay(callbackRetryDelayMs)
                return@repeat
            }
            if (verified.status == "pending" && callbackResultCode == 0 && attempt + 1 < attempts) {
                logger.info("M-Pesa reported a successful callback but its query is still pending; retry {}/{} for paymentId={}", attempt + 1, attempts, payment.id)
                delay(callbackRetryDelayMs)
                return@repeat
            }
            if (verified.status == payment.status) return false
            return applyWebhookEvent(
                ApplyWebhookEvent.Command(
                    provider = payment.provider,
                    reference = payment.providerReference,
                    status = verified.status,
                    verifiedVia = "callback_reconciliation",
                    amount = verified.amount,
                    currency = verified.currency,
                    paidAt = verified.paidAt
                )
            )
        }
        logger.warn("M-Pesa payment remains unconfirmed after callback reconciliation; paymentId={} callbackResultCode={}", payment.id, callbackResultCode)
        return false
    }

    private companion object {
        const val CALLBACK_SUCCESS_ATTEMPTS = 4
        const val CALLBACK_RETRY_DELAY_MS = 3_000L
        val logger = org.slf4j.LoggerFactory.getLogger(ReconcilePayments::class.java)
    }
}
