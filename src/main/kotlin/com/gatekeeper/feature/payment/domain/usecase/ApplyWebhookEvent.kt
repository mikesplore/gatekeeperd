package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Applies a verified provider event idempotently to the payment record. */
class ApplyWebhookEvent(
    private val payments: PaymentRepository,
    private val effects: PaymentEffects
) {
    operator fun invoke(command: Command): Boolean {
        var current = payments.findByProviderReference(command.provider, command.reference)
        if (current?.status == command.status) return false
        val amount = command.amount ?: current?.amount ?: BigDecimal.ZERO
        if (command.status == "success") {
            if (amount <= BigDecimal.ZERO) return false
            if (current?.serviceId == null && command.serviceId == null) return false
            val projectId = current?.projectId ?: command.projectId ?: return false
            if (!effects.acceptSuccessfulPayment(current, projectId, command.serviceId, amount, command.currency)) return false
            if (current == null) current = payments.create(
                projectId, command.provider, command.reference, amount, command.status, command.rawPayload,
                serviceId = command.serviceId
            )
        }
        if (current == null && command.status == "failed" && command.projectId != null) {
            current = payments.create(
                command.projectId, command.provider, command.reference, BigDecimal.ZERO, "failed", command.rawPayload,
                serviceId = command.serviceId
            )
        }
        if (current == null) return false
        if (command.status == "reversed") effects.paymentReversed(current)
        val paidAt = if (command.status == "success") command.paidAt ?: LocalDateTime.now() else command.paidAt
        payments.updateStatus(current.provider, current.providerReference, command.status, command.verifiedVia, paidAt, command.amount)
        if (command.status == "success") effects.paymentSucceeded(current, amount, command.currency, paidAt, command.actor)
        if (command.status == "failed" || command.status == "abandoned") effects.paymentFailed(current)
        return true
    }

    data class Command(
        val provider: String,
        val reference: String,
        val status: String,
        val verifiedVia: String,
        val amount: BigDecimal? = null,
        val currency: String? = null,
        val paidAt: LocalDateTime? = null,
        val projectId: UUID? = null,
        val serviceId: UUID? = null,
        val rawPayload: String? = null,
        val actor: String = "system"
    )
}

interface PaymentEffects {
    fun acceptSuccessfulPayment(payment: com.gatekeeper.feature.payment.domain.model.Payment?, projectId: UUID, serviceId: UUID?, amount: BigDecimal, currency: String?): Boolean
    fun paymentSucceeded(payment: com.gatekeeper.feature.payment.domain.model.Payment, amount: BigDecimal, currency: String?, paidAt: LocalDateTime?, actor: String)
    fun paymentFailed(payment: com.gatekeeper.feature.payment.domain.model.Payment)
    fun paymentReversed(payment: com.gatekeeper.feature.payment.domain.model.Payment)
}
