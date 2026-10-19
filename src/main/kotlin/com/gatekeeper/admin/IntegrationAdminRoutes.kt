package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get

@Serializable
private data class OutboxEventResponse(val id: String, val eventType: String, val idempotencyKey: String, val payload: String, val attempts: Int, val status: String, val lastError: String?)
@Serializable
private data class OutboxReplayResponse(val replayed: Boolean, val id: String, val status: String, val error: String? = null)

fun Application.configureIntegrationAdminRoutes() {
    val outbox = get<IntegrationOutboxRepository>()
    routing { authenticate("auth-jwt") {
        get("/api/admin/integrations/outbox") {
            val events = outbox.pending().map { event ->
                OutboxEventResponse(event.id.toString(), event.eventType, event.idempotencyKey, event.payload, event.attempts, event.status, event.lastError)
            }
            call.respond(events)
        }
        post("/api/admin/integrations/outbox/{id}/replay") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_id", "Invalid outbox event id"); return@post }
            val event = outbox.replayAndClaim(id)
            if (event == null) { call.respondError(HttpStatusCode.NotFound, "outbox_event_not_found", "Undelivered outbox event not found"); return@post }
            val result = ScribedIntegrationClient.deliverWithResult(event, call.application.get<PaymentRepository>(), call.application.get<ProjectBalanceAdapter>())
            if (result.delivered) outbox.markDelivered(id)
            else outbox.markFailed(id, result.error ?: "Integration delivery failed", event.attempts)
            call.respond(OutboxReplayResponse(result.delivered, id.toString(), if (result.delivered) "delivered" else "queued_for_retry", result.error))
        }
    } }
}
