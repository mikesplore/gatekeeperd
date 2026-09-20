package com.gatekeeper.payments

import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.tables.AdjustmentType
import java.math.BigDecimal

object ProjectBalanceService {
    fun outstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal {
        val originalCharge = project.baseAmount ?: project.amountDue ?: BigDecimal.ZERO
        val additionalCharges = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.ADDITIONAL_CHARGE)
        val discounts = ProjectAdjustmentRepository.totalForProject(project.id, AdjustmentType.DISCOUNT)
        val successfulPayments = PaymentRepository.successfulAmountForProject(project.id)
        return (originalCharge + additionalCharges - discounts - successfulPayments).max(BigDecimal.ZERO)
    }

    fun requireOutstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal =
        outstandingBalance(project).takeIf { it > BigDecimal.ZERO }
            ?: error("Project has no outstanding balance")

    fun requireAvailableForNewPayment(project: ProjectRepository.ProjectRecord): BigDecimal {
        val available = outstandingBalance(project) - PaymentRepository.pendingAmountForProject(project.id)
        return available.takeIf { it > BigDecimal.ZERO } ?: error("Project has no available outstanding balance")
    }
}
