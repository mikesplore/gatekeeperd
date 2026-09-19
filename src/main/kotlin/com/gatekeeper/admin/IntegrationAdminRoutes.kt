package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Application.configureIntegrationAdminRoutes() {
    routing { authenticate("auth-jwt") {
        get("/api/admin/integrations/outbox") { call.respond(IntegrationOutboxRepository.pending()) }
        post("/api/admin/integrations/outbox/{id}/replay") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (id == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_id", "Invalid outbox event id"); return@post }
            IntegrationOutboxRepository.replay(id)
            call.respond(mapOf("replayed" to true, "id" to id.toString()))
        }
    } }
}
