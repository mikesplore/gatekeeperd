package com.gatekeeper.feature.service.application

import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository
import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceRepository
import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceIntegrationPort
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Gatekeeper-owned billing ledger. Scribed is consulted only to import legacy invoices. */
class ServiceInvoiceApplicationService(
    private val invoices: ServiceInvoiceRepository,
    private val services: ServiceRepository,
    private val payments: PaymentRepository,
    private val adjustments: ProjectAdjustmentRepository,
    private val integration: ServiceInvoiceIntegrationPort
) {
    data class InvoiceView(
        val serviceId: UUID,
        val description: String,
        val amount: BigDecimal,
        val paid: BigDecimal,
        val currency: String,
        val dueDate: LocalDate?,
        val scribedInvoiceId: String?,
        val payments: List<PaymentView>
    )
    data class PaymentView(val id: UUID, val providerReference: String, val amount: BigDecimal)

    fun find(serviceId: UUID): ServiceInvoiceRepository.Record? = invoices.findByServiceId(serviceId)
        ?: importLegacy(serviceId)

    fun view(serviceId: UUID): InvoiceView? {
        val invoice = find(serviceId) ?: return null
        val paid = payments.findByProjectId(invoice.projectId)
            .filter { it.serviceId == serviceId && it.status.equals("success", true) }
            .fold(invoice.openingPaid) { sum, payment -> sum + payment.amount }
        val successfulPayments = payments.findByProjectId(invoice.projectId)
            .filter { it.serviceId == serviceId && it.status.equals("success", true) }
            .map { PaymentView(it.id, it.providerReference, it.amount) }
        return InvoiceView(serviceId, invoice.description, invoice.amount, paid, invoice.currency, invoice.dueDate, invoice.scribedInvoiceId, successfulPayments)
    }

    fun create(serviceId: UUID, projectId: UUID, description: String, amount: BigDecimal, currency: String, dueDate: LocalDate? = null): ServiceInvoiceRepository.Record {
        require(amount > BigDecimal.ZERO) { "Invoice amount must be greater than zero" }
        require(currency.length == 3) { "Invoice currency must be a three-letter code" }
        require(invoices.findByServiceId(serviceId) == null) { "An invoice already exists for this service" }
        return invoices.create(ServiceInvoiceRepository.Record(
            serviceId = serviceId, projectId = projectId, description = description.trim(), amount = amount,
            currency = currency.uppercase(), dueDate = dueDate, openingPaid = BigDecimal.ZERO,
            scribedInvoiceId = null, createdAt = LocalDateTime.now()
        ))
    }

    fun balance(serviceId: UUID): BigDecimal? {
        val invoice = find(serviceId) ?: return null
        val charge = adjustments.totalForService(serviceId, AdjustmentType.ADDITIONAL_CHARGE)
        val discounts = adjustments.totalForService(serviceId, AdjustmentType.DISCOUNT)
        val paid = payments.findByProjectId(invoice.projectId)
            .filter { it.serviceId == serviceId && it.status.equals("success", true) }
            .fold(invoice.openingPaid) { sum, payment -> sum + payment.amount }
        return (invoice.amount + charge - discounts - paid).max(BigDecimal.ZERO)
    }

    suspend fun ensureDocument(project: ProjectRecord, serviceId: UUID, serviceName: String): Boolean {
        val invoice = find(serviceId) ?: return false
        return integration.ensureDocument(project, serviceId, serviceName, invoice.description, invoice.amount)
    }

    private fun importLegacy(serviceId: UUID): ServiceInvoiceRepository.Record? {
        val service = services.findById(serviceId) ?: return null
        val legacyInvoice = kotlinx.coroutines.runBlocking { integration.findLegacyInvoice(serviceId) } ?: return null
        val localPaymentsIncluded = payments.findByProjectId(service.projectId)
            .filter { it.serviceId == serviceId && it.status.equals("success", true) && it.reference in legacyInvoice.paymentReferences }
            .fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }
        val openingPaid = (legacyInvoice.paid - localPaymentsIncluded).max(BigDecimal.ZERO)
        // Scribed reports the adjusted invoice total. Gatekeeper stores adjustments separately.
        val localAdjustmentTotal = adjustments.totalForService(serviceId, AdjustmentType.ADDITIONAL_CHARGE) -
            adjustments.totalForService(serviceId, AdjustmentType.DISCOUNT)
        val baseAmount = legacyInvoice.amount - localAdjustmentTotal
        require(baseAmount > BigDecimal.ZERO) { "Imported invoice base amount must be greater than zero" }
        val imported = ServiceInvoiceRepository.Record(
            serviceId = serviceId, projectId = service.projectId,
            description = legacyInvoice.description,
            amount = baseAmount, currency = legacyInvoice.currency,
            dueDate = legacyInvoice.dueDate, openingPaid = openingPaid,
            scribedInvoiceId = legacyInvoice.id,
            createdAt = LocalDateTime.now()
        )
        return invoices.create(imported)
    }
}
