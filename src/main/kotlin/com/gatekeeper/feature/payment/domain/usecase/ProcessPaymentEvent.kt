package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class ProcessPaymentEvent(
    private val payments: PaymentRepository,
    private val events: PaymentEventRepository,
    private val projects: PaymentProjectPort,
    private val applyWebhookEvent: ApplyWebhookEvent
) {
    operator fun invoke(command: Command): Outcome {
        if (events.alreadyRecorded(command.dedupeKey)) return Outcome.DUPLICATE
        val payment = command.reference?.let { payments.findByProviderReference(command.provider, it) }
        // A provider reference maps to the persisted payment target. The legacy
        // project metadata is only a fallback when no local payment exists.
        val projectId = payment?.projectId ?: command.projectId ?: command.projectSlug?.let { projects.find(it)?.id }
        val serviceId = payment?.serviceId
        val eventId = events.recordIfNew(
            command.dedupeKey, command.eventType, command.rawPayload, projectId, payment?.id, serviceId, command.reference, command.provider
        ) ?: return Outcome.DUPLICATE

        if (command.validationError != null) {
            events.markFailed(eventId, command.validationError)
            return Outcome.REJECTED
        }

        if (command.status != null) {
            if (command.reference.isNullOrBlank()) {
                events.markFailed(eventId, "Payment event is missing its reference")
                return Outcome.REJECTED
            }
            val applied = applyWebhookEvent(
                ApplyWebhookEvent.Command(
                    provider = command.provider,
                    reference = command.reference,
                    status = command.status,
                    verifiedVia = command.verifiedVia,
                    amount = command.amount,
                    currency = command.currency,
                    paidAt = command.paidAt,
                    projectId = projectId,
                    serviceId = serviceId,
                    rawPayload = command.rawPayload,
                    actor = command.actor
                )
            )
            if (!applied) {
                events.markFailed(eventId, "Payment event could not be applied")
                return Outcome.REJECTED
            }
        }
        events.markProcessed(eventId)
        return Outcome.PROCESSED
    }

    data class Command(
        val provider: String,
        val eventType: String,
        val dedupeKey: String,
        val rawPayload: String,
        val reference: String? = null,
        val projectId: UUID? = null,
        val projectSlug: String? = null,
        val status: String? = null,
        val verifiedVia: String = "webhook",
        val amount: BigDecimal? = null,
        val currency: String? = null,
        val paidAt: LocalDateTime? = null,
        val validationError: String? = null,
        val actor: String = "system"
    )

    enum class Outcome { PROCESSED, DUPLICATE, REJECTED }
}
