package com.gatekeeper.payments

import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.tables.AdjustmentType
import java.math.BigDecimal

object ProjectBalanceService {
    fun originalCharge(project: ProjectRepository.ProjectRecord): BigDecimal = project.baseAmount ?: project.amountDue ?: BigDecimal.ZERO
    fun additionalCharges(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.ADDITIONAL_CHARGE)
    fun discounts(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.DISCOUNT)
    fun successfulPayments(project: ProjectRepository.ProjectRecord): BigDecimal = PaymentRepository.successfulAmountForProject(project.id)

    fun outstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal {
        return (originalCharge(project) + additionalCharges(project) - discounts(project) - successfulPayments(project)).max(BigDecimal.ZERO)
    }

    fun requireOutstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal =
        outstandingBalance(project).takeIf { it > BigDecimal.ZERO }
            ?: error("Project has no outstanding balance")

    fun requireAvailableForNewPayment(
        project: ProjectRepository.ProjectRecord,
        requestedAmount: BigDecimal? = null
    ): BigDecimal {
        val available = outstandingBalance(project) - PaymentRepository.pendingAmountForProject(project.id)
        require(available > BigDecimal.ZERO) { "Project has no available outstanding balance" }
        val amount = requestedAmount ?: available
        require(amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(amount <= available) { "Payment amount exceeds the available outstanding balance" }
        require(amount.scale().coerceAtLeast(0) <= 2) { "Payment amount cannot have more than two decimal places" }
        return amount
    }

    suspend fun requireAvailableForNewPaymentWithReconciliation(
        project: ProjectRepository.ProjectRecord,
        requestedAmount: BigDecimal? = null
    ): BigDecimal {
        val outstanding = outstandingBalance(project)
        val pending = PaymentRepository.pendingAmountForProject(project.id)
        val available = outstanding - pending
        val needsReconciliation = pending > BigDecimal.ZERO &&
            (available <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > available))
        if (needsReconciliation) {
            PaymentRepository.findPendingByProjectId(project.id).forEach { payment ->
                PaymentReconciliationService.reconcile(payment)
            }
        }
        val currentProject = ProjectRepository.findById(project.id) ?: project
        val currentOutstanding = outstandingBalance(currentProject)
        val currentPending = PaymentRepository.pendingAmountForProject(currentProject.id)
        val currentAvailable = currentOutstanding - currentPending
        if (currentPending > BigDecimal.ZERO &&
            (requestedAmount == null || requestedAmount <= currentOutstanding) &&
            (currentAvailable <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > currentAvailable))
        ) {
            error("A payment for this balance is still pending. Try again after it is confirmed or canceled.")
        }
        return requireAvailableForNewPayment(currentProject, requestedAmount)
    }
}
