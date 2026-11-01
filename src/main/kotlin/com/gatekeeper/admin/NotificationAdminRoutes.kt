package com.gatekeeper.admin

import com.gatekeeper.api.dto.PaginatedResponse
import com.gatekeeper.api.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.time.LocalDateTime
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

fun Route.registerNotificationAdminRoutes(dependencies: OperationsAdminDependencies) {
    val notificationRepository = dependencies.notificationRepository

    get("/api/admin/notifications") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val principal = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        val projectId =
            call.request.queryParameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val severity = call.request.queryParameters["severity"]
        val action = call.request.queryParameters["type"]
        val from =
            call.request.queryParameters["from"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val to =
            call.request.queryParameters["to"]?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val archived = call.request.queryParameters["includeArchived"] == "true"
        val notifications = notificationRepository.list(
            principal,
            severity,
            action,
            projectId,
            from,
            to,
            archived,
            limit,
            offset
        ).map { row ->
            NotificationResponse(
                row.id.toString(),
                row.title,
                row.message,
                row.severity,
                row.action,
                row.createdAt.toString(),
                row.readAt != null
            )
        }
        val total = notificationRepository.count(principal, severity, action, projectId, from, to, archived)
        call.respond(
            PaginatedResponse(
                notifications,
                total,
                limit,
                offset,
                offset + notifications.size < total
            )
        )
    }

    post("/api/admin/notifications/{id}/{state}") {
        val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_notification_id",
                "Invalid notification ID"
            ); return@post
        }
        val state = call.parameters["state"] ?: ""
        if (state !in setOf("read", "dismissed", "archived")) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_notification_state",
                "State must be read, dismissed, or archived"
            ); return@post
        }
        val recipient = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        if (!notificationRepository.mark(id, recipient, state)) {
            call.respondError(
                HttpStatusCode.NotFound,
                "notification_not_found",
                "Notification not found"
            ); return@post
        }
        call.respond(mapOf("status" to state, "id" to id.toString()))
    }

    get("/api/admin/notifications/stream") {
        val recipient = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            try {
                var lastSeen: String? = null
                repeat(20) {
                    val latest = notificationRepository.list(recipient, null, null, null, null, null, false, 10)
                    val fresh = latest.filter { it.id.toString() != lastSeen }
                    if (fresh.isNotEmpty()) {
                        lastSeen = fresh.first().id.toString()
                        val payload = fresh.map {
                            NotificationResponse(
                                it.id.toString(),
                                it.title,
                                it.message,
                                it.severity,
                                it.action,
                                it.createdAt.toString(),
                                it.readAt != null
                            )
                        }
                        write("data: ${kotlinx.serialization.json.Json.encodeToString(payload)}\n\n")
                        flush()
                    } else {
                        write(": heartbeat\n\n")
                        flush()
                    }
                    kotlinx.coroutines.delay(3000.milliseconds)
                }
            } catch (_: Throwable) {
                // The browser commonly closes this stream during navigation or reload.
            }
        }
    }
}
