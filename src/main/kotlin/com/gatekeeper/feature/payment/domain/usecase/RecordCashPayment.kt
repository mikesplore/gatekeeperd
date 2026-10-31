package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import com.gatekeeper.plugins.DistributedLock

class RecordCashPayment(
    private val projects: PaymentProjectPort,
    private val payments: PaymentRepository,
    private val applyWebhookEvent: ApplyWebhookEvent
) {
    operator fun invoke(command: Command): Result<Payment> = runCatching {
        require(command.amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        val serviceId = command.serviceId
        DistributedLock.withLock("payment-initiation:$serviceId") {
            val project = projects.findForService(serviceId) ?: error("Service not found")
            val reference = command.receiptNumber?.trim()?.takeIf(String::isNotEmpty)?.let { "cash-$it" }
                ?: "cash-${UUID.randomUUID()}"
            check(applyWebhookEvent(
                ApplyWebhookEvent.Command(
                    provider = "cash", reference = reference, status = "success", verifiedVia = "manual_cash",
                    amount = command.amount, currency = command.currency ?: project.currency,
                    paidAt = command.paidAt ?: LocalDateTime.now(), projectId = project.id, serviceId = serviceId,
                    rawPayload = command.notes?.trim()?.takeIf(String::isNotEmpty), actor = command.actor
                )
            )) { "Cash payment exceeds the service invoice balance or has an invalid currency" }
            payments.findByProviderReference("cash", reference) ?: error("Recorded cash payment could not be loaded")
        }
    }

    data class Command(
        val serviceId: UUID,
        val amount: BigDecimal,
        val currency: String?,
        val paidAt: LocalDateTime?,
        val receiptNumber: String?,
        val notes: String?,
        val actor: String
    )
}
