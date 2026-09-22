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
import org.slf4j.MDC
import org.slf4j.event.Level
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.Monitoring")

class NotFoundException(override val message: String) : RuntimeException(message)

fun Application.configureMonitoring() {
    intercept(ApplicationCallPipeline.Setup) {
        val requestId = call.request.headers["X-Request-ID"]
            ?.trim()
            ?.takeIf { it.length in 1..128 && it.all { character -> character.isLetterOrDigit() || character in "-_." } }
            ?: UUID.randomUUID().toString()
        call.response.headers.append("X-Request-ID", requestId)
        MDC.put("requestId", requestId)
        val span = Telemetry.tracer.spanBuilder("${call.request.httpMethod.value} ${call.request.path()}").setAttribute("http.method", call.request.httpMethod.value).setAttribute("http.route", call.request.path()).startSpan()
        span.makeCurrent().use {
        try {
            proceed()
        } finally {
            span.end()
            MDC.remove("requestId")
        }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            val path = call.request.path()
            if (call.request.httpMethod == HttpMethod.Get &&
                path.matches(Regex("/api/admin/projects/[^/]+/invoice/?"))) {
                call.respondError(
                    HttpStatusCode.NotFound,
                    "invoice_unavailable",
                    "No Scribed invoice is available for this project"
                )
            } else {
                call.respondError(
                    HttpStatusCode.NotFound,
                    "route_not_found",
                    "No route matches ${call.request.httpMethod.value} $path"
                )
            }
        }
        exception<SerializationException> { call, ex ->
            logger.error("JSON serialization failed for ${call.request.httpMethod.value} ${call.request.path()}", ex)
            call.respondError(
                HttpStatusCode.InternalServerError,
                "serialization_error",
                "Failed to encode or decode JSON for this request"
            )
        }
        exception<NotFoundException> { call, ex ->
            call.respondError(HttpStatusCode.NotFound, "not_found", ex.message)
        }
        exception<Throwable> { call, ex ->
            logger.error("Unhandled exception", ex)
            call.respondError(HttpStatusCode.InternalServerError, "internal_server_error", "An unexpected error occurred")
        }
    }
}
