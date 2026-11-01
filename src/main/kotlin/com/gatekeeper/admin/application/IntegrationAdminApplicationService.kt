package com.gatekeeper.admin.application


import com.gatekeeper.admin.model.*
import com.gatekeeper.api.HttpApiException
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import java.util.UUID

class IntegrationAdminApplicationService(
    private val outbox: IntegrationOutboxRepository,
    private val scribed: ScribedIntegrationClient,
    private val payments: PaymentRepository,
    private val balances: ProjectBalanceAdapter
) {
    fun pendingEvents(): List<OutboxEventResponse> = outbox.pending().map { event ->
        OutboxEventResponse(event.id.toString(), event.eventType, event.idempotencyKey, event.payload,
            event.attempts, event.status, event.lastError)
    }

    suspend fun replay(idValue: String?): OutboxReplayResponse {
        val id = idValue?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw HttpApiException(HttpStatusCode.BadRequest, "invalid_id", "Invalid outbox event id")
        val event = outbox.replayAndClaim(id)
            ?: throw HttpApiException(HttpStatusCode.NotFound, "outbox_event_not_found", "Undelivered outbox event not found")
        val result = scribed.deliverWithResult(event, payments, balances, outbox)
        if (result.delivered) outbox.markDelivered(id)
        else outbox.markFailed(id, result.error ?: "Integration delivery failed", event.attempts)
        return OutboxReplayResponse(result.delivered, id.toString(),
            if (result.delivered) "delivered" else "queued_for_retry", result.error)
    }
}
