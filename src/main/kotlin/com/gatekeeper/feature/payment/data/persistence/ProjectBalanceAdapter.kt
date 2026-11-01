package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import java.math.BigDecimal

/** Persistence adapter for assembling the domain balance from project billing and payment data. */
class ProjectBalanceAdapter(
    private val payments: PaymentRepository,
    private val adjustments: ProjectAdjustmentRepository,
    private val invoices: ServiceInvoiceApplicationService,
    private val serviceRepository: ServiceRepository
) {
    fun originalCharge(project: ProjectRecord): BigDecimal = serviceInvoiceTotals(project).total
    fun additionalCharges(project: ProjectRecord): BigDecimal =
        adjustments.totalForProject(project.id, com.gatekeeper.db.tables.AdjustmentType.ADDITIONAL_CHARGE) +
            serviceRepository.listByProjectId(project.id).sumOf { adjustments.totalForService(it.id, com.gatekeeper.db.tables.AdjustmentType.ADDITIONAL_CHARGE) }
    fun discounts(project: ProjectRecord): BigDecimal =
        adjustments.totalForProject(project.id, com.gatekeeper.db.tables.AdjustmentType.DISCOUNT) +
            serviceRepository.listByProjectId(project.id).sumOf { adjustments.totalForService(it.id, com.gatekeeper.db.tables.AdjustmentType.DISCOUNT) }
    fun projectAdjustments(projectId: java.util.UUID) = adjustments.findByProjectId(projectId)
    fun serviceAdjustments(serviceId: java.util.UUID) = adjustments.findByServiceId(serviceId)
    fun successfulPayments(project: ProjectRecord): BigDecimal = serviceInvoiceTotals(project).paid

    fun financials(project: ProjectRecord): ProjectFinancials {
        val invoiceTotals = serviceInvoiceTotals(project)
        val original = invoiceTotals.total
        val additional = additionalCharges(project)
        val discounts = discounts(project)
        val paid = invoiceTotals.paid
        val outstanding = (original + additional - discounts - paid).max(BigDecimal.ZERO)
        return ProjectFinancials(original, additional, discounts, paid, outstanding)
    }

    fun outstandingBalance(project: ProjectRecord): BigDecimal = financials(project).outstanding

    private data class InvoiceTotals(val total: BigDecimal, val paid: BigDecimal)

    private fun serviceInvoiceTotals(project: ProjectRecord): InvoiceTotals = serviceRepository.listByProjectId(project.id)
        .mapNotNull { invoices.find(it.id) }
        .fold(InvoiceTotals(BigDecimal.ZERO, BigDecimal.ZERO)) { sum, invoice ->
            val paid = payments.findByProjectId(project.id)
                .filter { it.serviceId == invoice.serviceId && it.status.equals("success", true) }
                .fold(invoice.openingPaid) { value, payment -> value + payment.amount }
            InvoiceTotals(sum.total + invoice.amount, sum.paid + paid)
        }
}
