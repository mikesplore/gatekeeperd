package com.gatekeeper.payments

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import com.gatekeeper.integrations.ScribedIntegrationClient

object PaymentApplicationService {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.payments.PaymentApplicationService")

    fun applySuccessfulPayment(
        provider: PaymentProvider,
        reference: String,
        projectSlug: String,
        amount: BigDecimal,
        currency: String? = null,
        verifiedVia: String,
        paidAt: LocalDateTime = LocalDateTime.now(),
        rawPayload: String? = null,
        actor: String = "system"
    ): Boolean {
        val existing = PaymentRepository.findByProviderReference(provider, reference)
        if (existing != null && existing.gatewayStatus == "success") return false
        val project = ProjectRepository.findBySlug(projectSlug) ?: return false
        if (existing != null && existing.projectId != project.id) return false
        val amountDue = project.amountDue ?: return false
        if (amount <= BigDecimal.ZERO) return false
        if (!currency.isNullOrBlank() && !currency.equals(project.currency, ignoreCase = true)) return false
        val alreadyPaid = PaymentRepository.successfulAmountForProject(project.id) -
            if (existing?.gatewayStatus == "success") existing.amount else BigDecimal.ZERO
        val remaining = amountDue - alreadyPaid
        if (amount > remaining) return false

        if (existing == null) {
            PaymentRepository.create(
                projectId = project.id,
                provider = provider,
                providerReference = reference,
                authorizationUrl = null,
                amount = amount,
                status = "success",
                gatewayStatus = "success",
                rawWebhookPayload = rawPayload
            )
        } else {
            PaymentRepository.updateAmountByProviderReference(provider, reference, amount)
        }
        PaymentRepository.markGatewayStatusByProviderReference(provider, reference, "success", verifiedVia, paidAt)
        val totalPaid = alreadyPaid + amount
        if (totalPaid >= amountDue) {
            ProjectRepository.setStatusAndClearDueDate(project.id, "active")
        }
        AuditRepository.write(project.id, "payment_received", actor, "Payment ref=$reference provider=$provider amount=$amount totalPaid=$totalPaid/$amountDue verified via $verifiedVia")
        ProjectRepository.invalidateCache(project.slug)
        ScribedIntegrationClient.notifyPayment(project, provider.name.lowercase(), reference, amount.toPlainString(), currency ?: project.currency, paidAt.toString())
        logger.info("Payment applied: provider=$provider project=$projectSlug ref=$reference")
        return true
    }
}
