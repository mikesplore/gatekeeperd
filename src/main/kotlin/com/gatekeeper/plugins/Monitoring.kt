package com.gatekeeper.plugins

import com.gatekeeper.api.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.Monitoring")

class NotFoundException(override val message: String) : RuntimeException(message)

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }

    install(StatusPages) {
        exception<SerializationException> { call, _ ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
        }
        exception<NotFoundException> { call, ex ->
            call.respondError(HttpStatusCode.NotFound, "not_found", ex.message ?: "Resource not found")
        }
        exception<Throwable> { call, ex ->
            logger.error("Unhandled exception", ex)
            call.respondError(HttpStatusCode.InternalServerError, "internal_server_error", "An unexpected error occurred")
        }
    }
}
