package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.feature.payment.domain.model.ProjectBalance
import java.math.BigDecimal

/** Persistence adapter for assembling the domain balance from project billing and payment data. */
object ProjectBalanceAdapter {
    private val payments = ExposedPaymentRepository()
    fun originalCharge(project: ProjectRepository.ProjectRecord): BigDecimal = project.baseAmount ?: project.amountDue ?: BigDecimal.ZERO
    fun additionalCharges(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.ADDITIONAL_CHARGE)
    fun discounts(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.DISCOUNT)
    fun successfulPayments(project: ProjectRepository.ProjectRecord): BigDecimal = payments.successfulAmountForProject(project.id)

    fun outstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectBalance.calculate(
        project.id, project.currency, originalCharge(project), additionalCharges(project), discounts(project), successfulPayments(project)
    ).outstanding

    fun requireOutstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal =
        outstandingBalance(project).takeIf { it > BigDecimal.ZERO } ?: error("Project has no outstanding balance")

    fun requireAvailableForNewPayment(project: ProjectRepository.ProjectRecord, requestedAmount: BigDecimal? = null): BigDecimal {
        val available = outstandingBalance(project) - payments.pendingAmountForProject(project.id)
        require(available > BigDecimal.ZERO) { "Project has no available outstanding balance" }
        val amount = requestedAmount ?: available
        require(amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(amount <= available) { "Payment amount exceeds the available outstanding balance" }
        require(amount.scale().coerceAtLeast(0) <= 2) { "Payment amount cannot have more than two decimal places" }
        return amount
    }
}
