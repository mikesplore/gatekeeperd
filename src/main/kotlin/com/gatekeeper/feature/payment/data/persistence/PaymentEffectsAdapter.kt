package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ServiceRepository
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Adapter for project state and integration effects owned by other features. */
class PaymentEffectsAdapter(
    private val projectBalances: ProjectBalanceAdapter,
    private val auditRepository: AuditRepository,
    private val outbox: IntegrationOutboxRepository,
    private val scribed: ScribedIntegrationClient
) : PaymentEffects {
    override fun acceptSuccessfulPayment(payment: Payment?, projectId: java.util.UUID, amount: BigDecimal, currency: String?): Boolean {
        val project = ProjectRepository.findById(projectId) ?: return false
        if (!currency.isNullOrBlank() && !project.currency.equals(currency, ignoreCase = true)) return false
        val serviceId = payment?.serviceId ?: return false
        val invoice = runBlocking { scribed.serviceInvoiceStatus(serviceId.toString()) }
        if (invoice.status?.value !in 200..299) return false
        val invoiceData = invoice.body?.get("invoice")?.jsonObject ?: return false
        val serviceCurrency = invoiceData["currency"]?.jsonPrimitive?.contentOrNull
        if (!currency.isNullOrBlank() && !serviceCurrency.isNullOrBlank() && !currency.equals(serviceCurrency, ignoreCase = true)) return false
        val outstanding = invoiceData["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return false
        return outstanding > BigDecimal.ZERO && amount <= outstanding &&
            (payment == null || payment.status != "pending" || amount.compareTo(payment.amount) == 0)
    }

    override fun paymentSucceeded(payment: Payment, amount: BigDecimal, currency: String?, paidAt: LocalDateTime?, actor: String) {
        val project = ProjectRepository.findById(payment.projectId) ?: return
        if (payment.serviceId != null) {
            val invoice = runBlocking { scribed.serviceInvoiceStatus(payment.serviceId.toString()) }
            val balance = invoice.body?.get("invoice")?.jsonObject?.get("balance")?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
            if (balance != null && amount >= balance) ServiceRepository.clearAutoBlockForPayment(payment.serviceId)
            auditRepository.write(project.id, "payment_received", actor, "Service payment ref=${payment.reference} service=${payment.serviceId} amount=$amount")
            val timestamp = paidAt ?: LocalDateTime.now()
            scribed.notifyPayment(project, payment.provider, payment.reference, amount.toPlainString(), currency ?: project.currency, timestamp.toString(), outbox, payment.serviceId)
            return
        }
        auditRepository.write(project.id, "payment_received", actor, "Payment ref=${payment.reference} provider=${payment.provider} amount=$amount verified via payment_use_case")
        ProjectRepository.invalidateCache(project.slug)
        val timestamp = paidAt ?: LocalDateTime.now()
        scribed.notifyPayment(project, payment.provider, payment.reference, amount.toPlainString(), currency ?: project.currency, timestamp.toString(), outbox, payment.serviceId)
    }

    override fun paymentFailed(payment: Payment) {
        val project = ProjectRepository.findById(payment.projectId) ?: return
        auditRepository.write(project.id, "payment_failed", "system", "Payment failed for ref=${payment.reference}")
    }

    override fun paymentReversed(payment: Payment) {
        val project = ProjectRepository.findById(payment.projectId) ?: return
        if (payment.status == "success") {
            ProjectRepository.setStatusAndDueDate(project.id, "blocked", LocalDate.now(), blockReason = "payment_reversed")
            auditRepository.write(project.id, "blocked", "system", "Payment reversed (ref=${payment.reference})")
            ProjectRepository.invalidateCache(project.slug)
            scribed.notifySuspension(project.copy(status = "blocked", blockReason = "payment_reversed"), "payment_reversed", outbox)
        }
    }
}
