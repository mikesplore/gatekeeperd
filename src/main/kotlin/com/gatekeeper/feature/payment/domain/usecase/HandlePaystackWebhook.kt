package com.gatekeeper.feature.payment.domain.usecase

import java.math.BigDecimal

class HandlePaystackWebhook(
    private val processPaymentEvent: ProcessPaymentEvent
) {
    operator fun invoke(command: Command): ProcessPaymentEvent.Outcome {
        val status = when (command.eventType) {
            "charge.success" -> if (command.paymentStatus.equals("success", true)) "success" else null
            "charge.failed" -> "failed"
            "transfer.reversed", "charge.reversed" -> "reversed"
            else -> null
        }
        return processPaymentEvent(
            ProcessPaymentEvent.Command(
                provider = "paystack", eventType = command.eventType,
                dedupeKey = if (command.verifiedVia == "admin_replay") "admin-replay:${command.eventType}:${command.reference}" else "${command.eventType}:${command.reference}",
                rawPayload = command.rawPayload, reference = command.reference,
                projectSlug = command.projectSlug, status = status,
                verifiedVia = command.verifiedVia,
                amount = if (status == "success") BigDecimal.valueOf(command.amountKobo).movePointLeft(2) else null,
                currency = command.currency, actor = command.actor
            )
        )
    }

    data class Command(
        val eventType: String,
        val paymentStatus: String,
        val reference: String,
        val projectSlug: String?,
        val amountKobo: Long,
        val currency: String?,
        val rawPayload: String,
        val verifiedVia: String = "webhook",
        val actor: String = "system"
    )
}
