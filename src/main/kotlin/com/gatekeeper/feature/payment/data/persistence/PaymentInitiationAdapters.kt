package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import com.gatekeeper.feature.payment.domain.usecase.PaymentServicePort
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import java.math.BigDecimal
import java.util.UUID
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService

class PaymentProjectAdapter(
    private val projects: ProjectRepository,
    private val services: ServiceRepository
) : PaymentProjectPort {
    override fun find(slug: String): PaymentProject? = projects.findBySlug(slug)?.let {
        PaymentProject(it.id, it.slug, it.currency, it.customerEmail, it.domain, it.status)
    }

    override fun findForService(serviceId: UUID): PaymentProject? = services.findById(serviceId)
        ?.let { projects.findById(it.projectId) }
        ?.let { PaymentProject(it.id, it.slug, it.currency, it.customerEmail, it.domain, it.status) }
}

class PaymentServiceAdapter : PaymentServicePort {
    override fun belongsToProject(serviceRepository: ServiceRepository, projectId: UUID, serviceId: UUID): Boolean =
        serviceRepository.findByProjectAndId(projectId, serviceId) != null
}

class PaymentBalanceAdapter(
    private val payments: PaymentRepository,
    private val reconciliation: ReconcilePayments,
    private val projectBalances: ProjectBalanceAdapter,
    private val serviceInvoices: ServiceInvoiceApplicationService,
    private val projectRepository: ProjectRepository
) : PaymentBalancePort {
    override suspend fun requiresServiceScope(projectId: UUID): Boolean {
        return true
    }

    override suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?): BigDecimal =
        availableAmount( serviceRepository, projectId, requestedAmount,null)

    override suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?, serviceId: UUID?): BigDecimal {
        return availableServiceAmount(serviceRepository, projectId, serviceId ?: error("A service is required for payment"), requestedAmount)
    }

    private suspend fun availableServiceAmount(serviceRepository: ServiceRepository, projectId: UUID, serviceId: UUID, requestedAmount: BigDecimal?): BigDecimal {
        require(serviceRepository.findByProjectAndId(projectId, serviceId) != null) { "Service not found for this project" }
        validateInvoiceCurrency(projectId, serviceId)
        var balance = serviceInvoices.balance(serviceId) ?: error("No invoice is linked to this service")
        var pending = payments.pendingAmountForServiceId(serviceId)
        var available = balance - pending
        if (pending > BigDecimal.ZERO && (available <= BigDecimal.ZERO || requestedAmount?.let { it > available } == true)) {
            payments.findPendingByServiceId(serviceId)
                .filterNot { it.provider.equals("mpesa", ignoreCase = true) }
                .forEach { reconciliation.reconcile(it.provider, it.providerReference) }
            balance = serviceInvoices.balance(serviceId) ?: error("No invoice is linked to this service")
            pending = payments.pendingAmountForServiceId(serviceId)
            available = balance - pending
        }
        if (pending > BigDecimal.ZERO && (available <= BigDecimal.ZERO || requestedAmount?.let { it > available } == true)) {
            error("A payment for this service invoice is still pending. Try again after it is confirmed or canceled.")
        }
        val amount = requestedAmount ?: available
        require(available > BigDecimal.ZERO) { "Service has no outstanding invoice balance" }
        require(amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(amount <= available) { "Payment amount exceeds the available service balance" }
        require(amount.scale().coerceAtLeast(0) <= 2) { "Payment amount cannot have more than two decimal places" }
        return amount
    }

    private fun serviceInvoiceCurrency(serviceId: UUID): String? = serviceInvoices.find(serviceId)?.currency

    private fun validateInvoiceCurrency(projectId: UUID, serviceId: UUID) {
        val invoiceCurrency = serviceInvoiceCurrency(serviceId)
        val projectCurrency = projectRepository.findById(projectId)?.currency ?: error("Project not found")
        require(invoiceCurrency.isNullOrBlank() || invoiceCurrency.equals(projectCurrency, ignoreCase = true)) {
            "Service invoice currency must match the project currency"
        }
    }
}
