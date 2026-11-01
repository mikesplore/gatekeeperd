package com.gatekeeper.gate

import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.project.domain.repository.ProjectQueryRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ServiceInvoiceDue(
    val serviceId: UUID,
    val serviceName: String,
    val amountDue: BigDecimal,
    val currency: String,
    val dueDate: LocalDate?
)

class GateApplicationService(
    private val projects: ProjectQueryRepository,
    private val gate: GateService,
    private val services: ServiceRepository,
    private val serviceInvoices: ServiceInvoiceApplicationService
) {
    fun check(slug: String, domain: String? = null): GateResult = gate.check(slug, domain)
    fun findProject(slug: String) = projects.findGateTargetBySlug(slug)?.project

    suspend fun serviceInvoices(paywall: PaywallInfo): Pair<Boolean, List<ServiceInvoiceDue>> {
        val projectServices = services.listByProjectId(paywall.projectId)
        if (projectServices.size <= 1) return false to emptyList()
        var serviceBillingMode = false
        val invoices = projectServices.mapNotNull { service ->
            val invoice = serviceInvoices.find(service.id) ?: return@mapNotNull null
            serviceBillingMode = true
            val amountDue = serviceInvoices.balance(service.id) ?: return@mapNotNull null
            if (amountDue <= BigDecimal.ZERO) return@mapNotNull null
            val currency = invoice.currency
            val dueDate = invoice.dueDate
            ServiceInvoiceDue(service.id, service.name, amountDue, currency, dueDate)
        }
        return serviceBillingMode to invoices
    }
}
