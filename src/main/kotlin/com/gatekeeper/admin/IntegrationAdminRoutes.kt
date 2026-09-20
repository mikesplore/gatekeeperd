package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
private data class OutboxEventResponse(val id: String, val eventType: String, val idempotencyKey: String, val payload: String, val attempts: Int, val status: String, val lastError: String?)
@Serializable
private data class OutboxReplayResponse(val replayed: Boolean, val id: String, val status: String)

fun Application.configureIntegrationAdminRoutes() {
    routing { authenticate("auth-jwt") {
        get("/api/admin/integrations/outbox") {
            val events = IntegrationOutboxRepository.pending().map { event ->
                OutboxEventResponse(event.id.toString(), event.eventType, event.idempotencyKey, event.payload, event.attempts, event.status, event.lastError)
            }
            call.respond(events)
        }
        post("/api/admin/integrations/outbox/{id}/replay") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_id", "Invalid outbox event id"); return@post }
            val event = IntegrationOutboxRepository.replayAndClaim(id)
            if (event == null) { call.respondError(HttpStatusCode.NotFound, "outbox_event_not_found", "Undelivered outbox event not found"); return@post }
            val delivered = ScribedIntegrationClient.deliver(event)
            if (delivered) IntegrationOutboxRepository.markDelivered(id)
            else IntegrationOutboxRepository.markFailed(id, "Scribed delivery failed", event.attempts)
            call.respond(OutboxReplayResponse(delivered, id.toString(), if (delivered) "delivered" else "queued_for_retry"))
        }
    } }
}
