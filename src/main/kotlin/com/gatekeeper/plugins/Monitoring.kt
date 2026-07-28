package com.gatekeeper.plugins

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
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        }
        exception<NotFoundException> { call, ex ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "not_found")))
        }
        exception<Throwable> { call, ex ->
            logger.error("Unhandled exception", ex)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_server_error"))
        }
    }
}