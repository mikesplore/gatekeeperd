package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import java.math.BigDecimal
import java.util.UUID

class PaymentProjectAdapter : PaymentProjectPort {
    override fun find(slug: String): PaymentProject? = ProjectRepository.findBySlug(slug)?.let {
        PaymentProject(it.id, it.slug, it.currency, it.customerEmail, it.domain, it.status)
    }
}

class PaymentBalanceAdapter(
    private val payments: PaymentRepository,
    private val reconciliation: ReconcilePayments,
    private val projectBalances: ProjectBalanceAdapter
) : PaymentBalancePort {
    override suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?): BigDecimal {
        var project = ProjectRepository.findById(projectId) ?: error("Project not found")
        val initialOutstanding = projectBalances.outstandingBalance(project)
        val initialPending = payments.pendingAmountForProject(projectId)
        val initialAvailable = initialOutstanding - initialPending
        val needsReconciliation = initialPending > BigDecimal.ZERO &&
            (initialAvailable <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > initialAvailable))
        if (needsReconciliation) {
            payments.findPendingByProjectId(projectId).forEach { reconciliation.reconcile(it.provider, it.providerReference) }
            project = ProjectRepository.findById(projectId) ?: project
        }

        val outstanding = projectBalances.outstandingBalance(project)
        val pending = payments.pendingAmountForProject(projectId)
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
