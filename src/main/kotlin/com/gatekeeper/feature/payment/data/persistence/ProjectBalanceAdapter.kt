package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ServiceRepository
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** Persistence adapter for assembling the domain balance from project billing and payment data. */
class ProjectBalanceAdapter(
    private val payments: PaymentRepository,
    private val adjustments: ProjectAdjustmentRepository,
    private val scribed: ScribedIntegrationClient
) {
    fun originalCharge(project: ProjectRepository.ProjectRecord): BigDecimal = serviceInvoiceTotals(project).total
    fun additionalCharges(project: ProjectRepository.ProjectRecord): BigDecimal = BigDecimal.ZERO
    fun discounts(project: ProjectRepository.ProjectRecord): BigDecimal = BigDecimal.ZERO
    fun projectAdjustments(projectId: java.util.UUID) = adjustments.findByProjectId(projectId)
    fun serviceAdjustments(serviceId: java.util.UUID) = adjustments.findByServiceId(serviceId)
    fun successfulPayments(project: ProjectRepository.ProjectRecord): BigDecimal = serviceInvoiceTotals(project).paid

    fun financials(project: ProjectRepository.ProjectRecord): ProjectFinancials {
        val invoiceTotals = serviceInvoiceTotals(project)
        val original = invoiceTotals.total
        val additional = additionalCharges(project)
        val discounts = discounts(project)
        val paid = invoiceTotals.paid
        val outstanding = invoiceTotals.outstanding
        return ProjectFinancials(original, additional, discounts, paid, outstanding)
    }

    fun outstandingBalance(project: ProjectRepository.ProjectRecord): BigDecimal = financials(project).outstanding

    private data class InvoiceTotals(val total: BigDecimal, val paid: BigDecimal, val outstanding: BigDecimal)

    private fun serviceInvoiceTotals(project: ProjectRepository.ProjectRecord): InvoiceTotals = runBlocking {
        ServiceRepository.listByProjectId(project.id).mapNotNull { service ->
            val lookup = scribed.serviceInvoiceStatus(service.id.toString())
            if (lookup.status?.value !in 200..299) return@mapNotNull null
            val invoice = lookup.body?.get("invoice")?.jsonObject ?: return@mapNotNull null
            val total = invoice["amount"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val paid = invoice["paid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val outstanding = invoice["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            InvoiceTotals(total, paid, outstanding)
        }.fold(InvoiceTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)) { sum, invoice ->
            InvoiceTotals(sum.total + invoice.total, sum.paid + invoice.paid, sum.outstanding + invoice.outstanding)
        }
    }
}
