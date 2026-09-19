package com.gatekeeper.payments

import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

object PaymentReconciliationService {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.payments.PaymentReconciliationService")
    private val clients = mutableMapOf<PaymentProvider, PaymentProviderClient>()

    fun register(client: PaymentProviderClient) {
        clients[client.provider] = client
    }

    suspend fun reconcile(payment: PaymentRepository.PaymentRecord): Boolean {
        val client = clients[payment.provider]
            ?: run {
                logger.warn("No reconciliation client registered for provider=${payment.provider}")
                return false
            }
        val verified = client.verify(payment.providerReference).getOrElse {
            logger.warn("Verification failed for provider=${payment.provider} ref=${payment.providerReference}", it)
            return false
        }

        when (verified.status) {
            VerifiedPaymentStatus.SUCCESS -> {
                val project = ProjectRepository.findById(payment.projectId) ?: return false
                val amount = verified.amount ?: payment.amount
                PaymentApplicationService.applySuccessfulPayment(
                    provider = verified.provider,
                    reference = verified.reference,
                    projectSlug = project.slug,
                    amount = amount,
                    currency = verified.currency,
                    verifiedVia = "reconciliation",
                    paidAt = verified.paidAt ?: LocalDateTime.now(),
                    rawPayload = verified.rawPayload
                )
            }
            VerifiedPaymentStatus.FAILED,
            VerifiedPaymentStatus.ABANDONED,
            VerifiedPaymentStatus.REVERSED -> {
                PaymentRepository.markGatewayStatusById(
                    payment.id,
                    verified.status.name.lowercase(),
                    "reconciliation"
                )
                true
            }
            VerifiedPaymentStatus.PENDING -> false
        }
        return true
    }
}
