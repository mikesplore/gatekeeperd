package com.gatekeeper.paystack

import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

object PaymentService {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.paystack.PaymentService")

    /**
     * Marks payment success, activates project, writes audit log, invalidates cache.
     * Idempotent — returns false if already processed as success.
     */
    fun applySuccessfulPayment(
        reference: String,
        projectSlug: String,
        amountNaira: BigDecimal,
        verifiedVia: String,
        paidAt: LocalDateTime = LocalDateTime.now(),
        rawPayload: String? = null
    ): Boolean {
        val existing = PaymentRepository.findByReference(reference)
        if (existing != null && existing.gatewayStatus == "success") {
            logger.info("Payment already success (idempotent), ref=$reference")
            return false
        }

        val project = ProjectRepository.findBySlug(projectSlug)
            ?: run {
                logger.warn("applySuccessfulPayment: project not found slug=$projectSlug ref=$reference")
                return false
            }

        if (existing == null) {
            PaymentRepository.create(
                projectId = project.id,
                paystackReference = reference,
                authorizationUrl = null,
                amount = amountNaira,
                status = "success",
                gatewayStatus = "success",
                rawWebhookPayload = rawPayload
            )
            PaymentRepository.markGatewayStatus(reference, "success", verifiedVia, paidAt)
        } else {
            PaymentRepository.markGatewayStatus(reference, "success", verifiedVia, paidAt)
        }

        ProjectRepository.setStatusAndClearDueDate(project.id, "active")
        AuditRepository.write(
            projectId = project.id,
            action = "payment_received",
            actor = "system",
            reason = "Payment ref=$reference verified via $verifiedVia; due date cleared"
        )
        ProjectRepository.invalidateCache(projectSlug)

        logger.info("Payment applied and project activated: $projectSlug, ref=$reference via $verifiedVia")
        return true
    }

    fun handleChargeFailed(reference: String, projectSlug: String?, rawPayload: String?) {
        val existing = PaymentRepository.findByReference(reference)
        if (existing != null && existing.gatewayStatus in setOf("success", "failed", "abandoned", "reversed")) {
            logger.info("Charge failed webhook idempotent skip, ref=$reference status=${existing.gatewayStatus}")
            return
        }

        val project = when {
            projectSlug != null -> ProjectRepository.findBySlug(projectSlug)
            existing != null -> ProjectRepository.findById(existing.projectId)
            else -> null
        }

        if (existing == null && project != null) {
            PaymentRepository.create(
                projectId = project.id,
                paystackReference = reference,
                authorizationUrl = null,
                amount = BigDecimal.ZERO,
                status = "failed",
                gatewayStatus = "failed",
                rawWebhookPayload = rawPayload
            )
        } else if (existing != null) {
            PaymentRepository.markGatewayStatus(reference, "failed", "webhook")
        }

        if (project != null) {
            AuditRepository.write(
                projectId = project.id,
                action = "payment_failed",
                actor = "system",
                reason = "Charge failed for ref=$reference"
            )
            logger.info("Payment failed recorded for ${project.slug}, ref=$reference")
        }
    }

    fun handleReversal(reference: String) {
        val payment = PaymentRepository.findByReference(reference) ?: run {
            logger.warn("Reversal webhook: payment not found ref=$reference")
            return
        }

        if (payment.gatewayStatus == "reversed") {
            logger.info("Reversal webhook idempotent skip, ref=$reference")
            return
        }

        val wasSuccess = payment.gatewayStatus == "success"
        PaymentRepository.markGatewayStatus(reference, "reversed", "webhook")

        val project = ProjectRepository.findById(payment.projectId) ?: return

        if (wasSuccess) {
            ProjectRepository.setStatusAndDueDate(
                project.id,
                "blocked",
                LocalDate.now()
            )
            AuditRepository.write(
                projectId = project.id,
                action = "blocked",
                actor = "system",
                reason = "Payment reversed (ref=$reference)"
            )
            ProjectRepository.invalidateCache(project.slug)
            logger.warn("Project re-blocked due to payment reversal: ${project.slug}, ref=$reference")
        }
    }
}
