package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.integrations.ScribedIntegrationClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Transitional adapter for project state and integration effects owned by legacy features. */
class LegacyPaymentEffects : PaymentEffects {
    override fun acceptSuccessfulPayment(payment: Payment?, projectId: java.util.UUID, amount: BigDecimal, currency: String?): Boolean {
        val project = ProjectRepository.findById(projectId) ?: return false
        if (!currency.isNullOrBlank() && !project.currency.equals(currency, ignoreCase = true)) return false
        val outstanding = ProjectBalanceAdapter.outstandingBalance(project)
        return outstanding > BigDecimal.ZERO && amount <= outstanding &&
            (payment == null || payment.status != "pending" || amount.compareTo(payment.amount) == 0)
    }

    override fun paymentSucceeded(payment: Payment, amount: BigDecimal, currency: String?, paidAt: LocalDateTime?, actor: String) {
        val project = ProjectRepository.findById(payment.projectId) ?: return
        val remaining = ProjectBalanceAdapter.outstandingBalance(project)
        if (remaining <= BigDecimal.ZERO) ProjectRepository.setStatusAndClearDueDate(project.id, "active")
        AuditRepository.write(project.id, "payment_received", actor, "Payment ref=${payment.reference} provider=${payment.provider} amount=$amount remaining=$remaining verified via payment_use_case")
        ProjectRepository.invalidateCache(project.slug)
        val timestamp = paidAt ?: LocalDateTime.now()
        ScribedIntegrationClient.notifyPayment(project, payment.provider, payment.reference, amount.toPlainString(), currency ?: project.currency, timestamp.toString())
        ScribedIntegrationClient.notifyLedger(project, "payment-${payment.provider}-${payment.reference}")
    }

    override fun paymentFailed(payment: Payment) {
        val project = ProjectRepository.findById(payment.projectId) ?: return
        AuditRepository.write(project.id, "payment_failed", "system", "Payment failed for ref=${payment.reference}")
    }

    override fun paymentReversed(payment: Payment) {
        val project = ProjectRepository.findById(payment.projectId) ?: return
        if (payment.status == "success") {
            ProjectRepository.setStatusAndDueDate(project.id, "blocked", LocalDate.now(), blockReason = "payment_reversed")
            AuditRepository.write(project.id, "blocked", "system", "Payment reversed (ref=${payment.reference})")
            ProjectRepository.invalidateCache(project.slug)
            ScribedIntegrationClient.notifySuspension(project.copy(status = "blocked", blockReason = "payment_reversed"), "payment_reversed")
        }
    }
}
