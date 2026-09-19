package com.gatekeeper.payments

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

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
        rawPayload: String? = null
    ): Boolean {
        val existing = PaymentRepository.findByProviderReference(provider, reference)
        if (existing != null && existing.gatewayStatus == "success") return false
        val project = ProjectRepository.findBySlug(projectSlug) ?: return false
        if (existing != null && existing.projectId != project.id) return false
        val expected = existing?.amount ?: project.amountDue
        if (expected == null || amount.compareTo(expected) != 0) return false
        if (!currency.isNullOrBlank() && !currency.equals(project.currency, ignoreCase = true)) return false

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
        }
        PaymentRepository.markGatewayStatusByProviderReference(provider, reference, "success", verifiedVia, paidAt)
        ProjectRepository.setStatusAndClearDueDate(project.id, "active")
        AuditRepository.write(project.id, "payment_received", "system", "Payment ref=$reference provider=$provider verified via $verifiedVia")
        ProjectRepository.invalidateCache(project.slug)
        logger.info("Payment applied: provider=$provider project=$projectSlug ref=$reference")
        return true
    }
}
