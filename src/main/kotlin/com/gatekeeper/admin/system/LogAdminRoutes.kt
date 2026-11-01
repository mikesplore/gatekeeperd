package com.gatekeeper.admin.system


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.respondError
import com.gatekeeper.core.ports.ProcessRunner
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.koin.ktor.ext.get

private val logAdminLogger = LoggerFactory.getLogger("com.gatekeeper.admin.LogAdminRoutes")





fun Application.configureLogAdminRoutes() {
    val processRunner = get<ProcessRunner>()
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            get("/api/admin/logs") {
                val query = call.request.queryParameters
                val rawLimit = query["limit"]?.toIntOrNull()
                val rawOffset = query["offset"]?.toIntOrNull()
                val requestedLevel = query["level"]?.trim()?.takeIf(String::isNotEmpty)
                val allowedLevels = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")

                if (rawLimit != null && rawLimit !in 1..100) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_limit", "limit must be between 1 and 100")
                    return@get
                }
                if (rawOffset != null && rawOffset < 0) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_offset", "offset must not be negative")
                    return@get
                }
                if (requestedLevel != null && requestedLevel.uppercase() !in allowedLevels) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_level",
                        "level must be TRACE, DEBUG, INFO, WARN, ERROR, or FATAL"
                    )
                    return@get
                }

                val limit = rawLimit ?: 25
                val offset = rawOffset ?: 0
                val search = query["search"]?.trim()?.take(200)?.takeIf(String::isNotEmpty)
                val result = try {
                    JournalLogApplicationService.query(processRunner, search, requestedLevel, limit, offset)
                } catch (error: Exception) {
                    logAdminLogger.error("Unable to read Gatekeeperd system journal", error)
                    call.respondError(
                        HttpStatusCode.ServiceUnavailable,
                        "logs_unavailable",
                        "System logs are unavailable. Ensure the Gatekeeperd service account can read the system journal."
                    )
                    return@get
                }

                call.respond(
                    AdminLogsPage(
                        logs = result.entries.map { entry ->
                            AdminLogEntry(entry.id, entry.timestamp, entry.level, entry.logger, entry.thread, entry.message, entry.requestId, entry.stackTrace)
                        },
                        total = result.total,
                        limit = result.limit,
                        offset = result.offset,
                        hasMore = result.hasMore,
                        truncated = result.truncated,
                        summary = AdminLogSummary(
                            scanned = result.summary.scanned,
                            errorLogs = result.summary.errorLogs,
                            warningLogs = result.summary.warningLogs,
                            requestCount = result.summary.requestCount,
                            successfulRequests = result.summary.successfulRequests,
                            failedRequests = result.summary.failedRequests,
                            successRate = result.summary.successRate,
                            errorRate = result.summary.errorRate
                        )
                    )
                )
            }
        }
    }
}
