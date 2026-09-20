package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
private data class OutboxEventResponse(val id: String, val eventType: String, val idempotencyKey: String, val payload: String, val attempts: Int)
@Serializable
private data class OutboxReplayResponse(val replayed: Boolean, val id: String)

fun Application.configureIntegrationAdminRoutes() {
    routing { authenticate("auth-jwt") {
        get("/api/admin/integrations/outbox") {
            val events = IntegrationOutboxRepository.pending().map { event ->
                OutboxEventResponse(event.id.toString(), event.eventType, event.idempotencyKey, event.payload, event.attempts)
            }
            call.respond(events)
        }
        post("/api/admin/integrations/outbox/{id}/replay") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_id", "Invalid outbox event id"); return@post }
            IntegrationOutboxRepository.replay(id)
            call.respond(OutboxReplayResponse(true, id.toString()))
        }
    } }
}
