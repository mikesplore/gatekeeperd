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

    fun requireAvailableForNewPayment(project: ProjectRepository.ProjectRecord): BigDecimal {
        val available = outstandingBalance(project) - PaymentRepository.pendingAmountForProject(project.id)
        return available.takeIf { it > BigDecimal.ZERO } ?: error("Project has no available outstanding balance")
    }
}
