package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ServiceRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import java.math.BigDecimal
import java.util.UUID
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PaymentProjectAdapter : PaymentProjectPort {
    override fun find(slug: String): PaymentProject? = ProjectRepository.findBySlug(slug)?.let {
        PaymentProject(it.id, it.slug, it.currency, it.customerEmail, it.domain, it.status)
    }
}

class PaymentBalanceAdapter(
    private val payments: PaymentRepository,
    private val reconciliation: ReconcilePayments,
    private val projectBalances: ProjectBalanceAdapter
) : PaymentBalancePort {
    override suspend fun requiresServiceScope(projectId: UUID): Boolean {
        val services = ServiceRepository.listByProjectId(projectId)
        if (services.size <= 1) return false
        var serviceBillingFound = false
        services.forEach { service ->
            val lookup = ScribedIntegrationClient.serviceInvoiceStatus(service.id.toString())
            when {
                lookup.status == HttpStatusCode.NotFound -> Unit
                lookup.status?.value in 200..299 -> serviceBillingFound = true
                else -> error("Unable to determine billing scope for this project")
            }
        }
        return serviceBillingFound
    }

    override suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?): BigDecimal =
        availableAmount(projectId, requestedAmount, null)

    override suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?, serviceId: UUID?): BigDecimal {
        if (serviceId != null) return availableServiceAmount(projectId, serviceId, requestedAmount)
        var project = ProjectRepository.findById(projectId) ?: error("Project not found")
        val initialOutstanding = projectBalances.outstandingBalance(project)
        val initialPending = payments.pendingAmountForProject(projectId)
        val initialAvailable = initialOutstanding - initialPending
        val needsReconciliation = initialPending > BigDecimal.ZERO &&
            (initialAvailable <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > initialAvailable))
        if (needsReconciliation) {
            payments.findPendingByProjectId(projectId).forEach { reconciliation.reconcile(it.provider, it.providerReference) }
            project = ProjectRepository.findById(projectId) ?: project
        }

        val outstanding = projectBalances.outstandingBalance(project)
        val pending = payments.pendingAmountForProject(projectId)
        val available = outstanding - pending
        if (pending > BigDecimal.ZERO && (requestedAmount == null || requestedAmount <= outstanding) &&
            (available <= BigDecimal.ZERO || (requestedAmount != null && requestedAmount > available))) {
            error("A payment for this balance is still pending. Try again after it is confirmed or canceled.")
        }
        val amount = requestedAmount ?: available
        require(available > BigDecimal.ZERO) { "Project has no available outstanding balance" }
        require(amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(amount <= available) { "Payment amount exceeds the available outstanding balance" }
        require(amount.scale().coerceAtLeast(0) <= 2) { "Payment amount cannot have more than two decimal places" }
        return amount
    }

    private suspend fun availableServiceAmount(projectId: UUID, serviceId: UUID, requestedAmount: BigDecimal?): BigDecimal {
        require(ServiceRepository.findByProjectAndId(projectId, serviceId) != null) { "Service not found for this project" }
        var invoice = serviceInvoice(projectId, serviceId)
        var balance = invoice.balance - paymentsNotYetInScribed(projectId, serviceId, invoice)
        var pending = payments.pendingAmountForServiceId(serviceId)
        var available = balance - pending
        if (pending > BigDecimal.ZERO && (available <= BigDecimal.ZERO || requestedAmount?.let { it > available } == true)) {
            payments.findPendingByServiceId(serviceId).forEach { reconciliation.reconcile(it.provider, it.providerReference) }
            invoice = serviceInvoice(projectId, serviceId)
            balance = invoice.balance - paymentsNotYetInScribed(projectId, serviceId, invoice)
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

    private data class ServiceInvoiceBalance(val balance: BigDecimal, val paymentReferences: Set<String>)

    private fun paymentsNotYetInScribed(projectId: UUID, serviceId: UUID, invoice: ServiceInvoiceBalance): BigDecimal =
        payments.findByProjectId(projectId)
            .filter { it.serviceId == serviceId && it.status.equals("success", ignoreCase = true) && it.reference !in invoice.paymentReferences }
            .fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }

    private suspend fun serviceInvoice(projectId: UUID, serviceId: UUID): ServiceInvoiceBalance {
        val lookup = ScribedIntegrationClient.serviceInvoiceStatus(serviceId.toString())
        if (lookup.status == HttpStatusCode.NotFound) error("No invoice is linked to this service")
        if (lookup.status?.value !in 200..299) error("Service invoice is temporarily unavailable")
        val invoice = lookup.body?.get("invoice")?.jsonObject ?: error("Service invoice is unavailable")
        val invoiceCurrency = invoice["currency"]?.jsonPrimitive?.contentOrNull
        val projectCurrency = ProjectRepository.findById(projectId)?.currency ?: error("Project not found")
        require(invoiceCurrency.isNullOrBlank() || invoiceCurrency.equals(projectCurrency, ignoreCase = true)) {
            "Service invoice currency must match the project currency"
        }
        val balance = invoice["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
            ?: error("Service invoice balance is unavailable")
        val references = invoice["payments"]?.let { element ->
            element as? kotlinx.serialization.json.JsonArray
        }?.mapNotNull { item ->
            runCatching { item.jsonObject["provider_reference"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }?.toSet().orEmpty()
        return ServiceInvoiceBalance(balance, references)
    }
}
