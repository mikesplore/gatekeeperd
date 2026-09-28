package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import java.util.UUID

class ReplayPaystackEvent(
    private val events: PaymentEventRepository,
    private val handlePaystackWebhook: HandlePaystackWebhook
) {
    operator fun invoke(id: UUID, command: HandlePaystackWebhook.Command): Result {
        val event = events.findForReplay(id) ?: return Result.NOT_FOUND
        if (event.processingStatus != "failed") return Result.NOT_FAILED
        val outcome = handlePaystackWebhook(command.copy(verifiedVia = "admin_replay"))
        if (outcome == ProcessPaymentEvent.Outcome.REJECTED) return Result.REPLAY_FAILED
        events.markProcessed(id)
        return Result.REPLAYED
    }

    enum class Result { NOT_FOUND, NOT_FAILED, REPLAY_FAILED, REPLAYED }
}
