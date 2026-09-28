package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.PaymentRepository as LegacyPaymentRepository
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import com.gatekeeper.payments.ProjectBalanceService
import java.math.BigDecimal
import java.util.UUID

class LegacyPaymentProjectAdapter : PaymentProjectPort {
    override fun find(slug: String): PaymentProject? = ProjectRepository.findBySlug(slug)?.let {
        PaymentProject(it.id, it.slug, it.currency, it.customerEmail)
    }
}

class LegacyPaymentBalanceAdapter(private val reconciliation: ReconcilePayments) : PaymentBalancePort {
    override suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?): BigDecimal {
        var project = ProjectRepository.findById(projectId) ?: error("Project not found")
        val initialOutstanding = ProjectBalanceService.outstandingBalance(project)
        val initialPending = LegacyPaymentRepository.pendingAmountForProject(projectId)
        val initialAvailable = initialOutstanding - initialPending
        val needsReconciliation = initialPending > BigDecimal.ZERO &&
            (initialAvailable <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > initialAvailable))
        if (needsReconciliation) {
            LegacyPaymentRepository.findPendingByProjectId(projectId).forEach { reconciliation.reconcile(it.provider.name.lowercase(), it.providerReference) }
            project = ProjectRepository.findById(projectId) ?: project
        }

        val outstanding = ProjectBalanceService.outstandingBalance(project)
        val pending = LegacyPaymentRepository.pendingAmountForProject(projectId)
        val available = outstanding - pending
        if (pending > BigDecimal.ZERO && (requestedAmount == null || requestedAmount <= outstanding) &&
            (available <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > available))) {
            error("A payment for this balance is still pending. Try again after it is confirmed or canceled.")
        }
        val amount = requestedAmount ?: available
        require(available > BigDecimal.ZERO) { "Project has no available outstanding balance" }
        require(amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(amount <= available) { "Payment amount exceeds the available outstanding balance" }
        require(amount.scale().coerceAtLeast(0) <= 2) { "Payment amount cannot have more than two decimal places" }
        return amount
    }
}
