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

}
