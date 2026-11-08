package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Adapter for project state and integration effects owned by other features. */
class PaymentEffectsAdapter(
    private val auditRepository: AuditRepository,
    private val projects: ProjectRepository,
    private val serviceRepository: ServiceRepository,
    private val serviceInvoices: ServiceInvoiceApplicationService
) : PaymentEffects {
    override fun acceptSuccessfulPayment(payment: Payment?, projectId: java.util.UUID, serviceId: java.util.UUID?, amount: BigDecimal, currency: String?): Boolean {
        val project = projects.findById(projectId) ?: return false
        if (!currency.isNullOrBlank() && !project.currency.equals(currency, ignoreCase = true)) return false
        val paymentServiceId = payment?.serviceId ?: serviceId ?: return false
        val invoice = serviceInvoices.find(paymentServiceId) ?: return false
        val serviceCurrency = invoice.currency
        if (!currency.isNullOrBlank() && !serviceCurrency.isNullOrBlank() && !currency.equals(serviceCurrency, ignoreCase = true)) return false
        val outstanding = serviceInvoices.balance(paymentServiceId) ?: return false
        return outstanding > BigDecimal.ZERO && amount <= outstanding &&
            (payment?.status != "pending" || amount.compareTo(payment.amount) == 0)
    }

    override fun paymentSucceeded(payment: Payment, amount: BigDecimal, currency: String?, paidAt: LocalDateTime?, actor: String) {
        val project = projects.findById(payment.projectId) ?: return
        if (payment.serviceId != null) {
            val balance = serviceInvoices.balance(payment.serviceId)
            if (balance != null && balance <= BigDecimal.ZERO) serviceRepository.clearAutoBlockForPayment(payment.serviceId)
            auditRepository.write(project.id, "payment_received", actor, "Service payment ref=${payment.reference} service=${payment.serviceId} amount=$amount")
            return
        }
        auditRepository.write(project.id, "payment_received", actor, "Payment ref=${payment.reference} provider=${payment.provider} amount=$amount verified via payment_use_case")
        projects.invalidateCache(project.slug)
    }

    override fun paymentFailed(payment: Payment) {
        val project = projects.findById(payment.projectId) ?: return
        auditRepository.write(project.id, "payment_failed", "system", "Payment failed for ref=${payment.reference}")
    }

    override fun paymentReversed(payment: Payment) {
        val project = projects.findById(payment.projectId) ?: return
        if (payment.status == "success") {
            if (payment.serviceId != null) {
                serviceRepository.blockForPaymentReversal(project.id, payment.serviceId)
                auditRepository.write(
                    project.id, "service_payment_reversed", "system",
                    "Payment reversed (ref=${payment.reference}) for service=${payment.serviceId}"
                )
                return
            }
            projects.setStatusAndDueDate(project.id, "blocked", LocalDate.now(), blockReason = "payment_reversed")
            auditRepository.write(project.id, "blocked", "system", "Payment reversed (ref=${payment.reference})")
            projects.invalidateCache(project.slug)
        }
    }
}
