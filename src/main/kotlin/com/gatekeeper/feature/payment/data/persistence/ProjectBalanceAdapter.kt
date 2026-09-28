package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.feature.payment.domain.model.ProjectBalance
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal

/** Persistence adapter for assembling the domain balance from project billing and payment data. */
class ProjectBalanceAdapter(private val payments: PaymentRepository) {
    fun originalCharge(project: ProjectRepository.ProjectRecord): BigDecimal = project.baseAmount ?: project.amountDue ?: BigDecimal.ZERO
    fun additionalCharges(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.ADDITIONAL_CHARGE)
    fun discounts(project: ProjectRepository.ProjectRecord): BigDecimal = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.DISCOUNT)
    fun successfulPayments(project: ProjectRepository.ProjectRecord): BigDecimal = payments.successfulAmountForProject(project.id)

    fun financials(project: ProjectRepository.ProjectRecord): ProjectFinancials {
        val original = originalCharge(project)
        val additional = additionalCharges(project)
        val discounts = discounts(project)
        val paid = successfulPayments(project)
        val outstanding = ProjectBalance.calculate(project.id, project.currency, original, additional, discounts, paid).outstanding
        return ProjectFinancials(original, additional, discounts, paid, outstanding)
    }

    fun outstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal = financials(project).outstanding
}
