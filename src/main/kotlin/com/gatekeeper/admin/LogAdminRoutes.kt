package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val logAdminLogger = LoggerFactory.getLogger("com.gatekeeper.admin.LogAdminRoutes")

@Serializable
data class AdminLogEntry(
    val id: String,
    val timestamp: String,
    val level: String,
    val logger: String? = null,
    val thread: String? = null,
    val message: String,
    val requestId: String? = null,
    val stackTrace: String? = null
)

@Serializable
data class AdminLogSummary(
    val scanned: Int,
    val errorLogs: Int,
    val warningLogs: Int,
    val requestCount: Int,
    val successfulRequests: Int,
    val failedRequests: Int,
    val successRate: Double? = null,
    val errorRate: Double? = null
)

@Serializable
data class AdminLogsPage(
    val logs: List<AdminLogEntry>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
    val truncated: Boolean,
    val summary: AdminLogSummary
)

internal data class JournalReadResult(val entries: List<AdminLogEntry>, val truncated: Boolean)

internal object JournalLogReader {
    private const val MAX_ENTRIES = 10_000
    private const val JOURNAL_UNIT = "gatekeeperd.service"
    private val json = Json { ignoreUnknownKeys = true }
    private val ansi = Regex("\\u001B\\[[;\\d]*m")
    private val statusCode = Regex("\\b([1-5][0-9]{2})\\s+[A-Za-z]")
    private val secretPattern = Regex(
        "(?i)(\\b(?:password|passwd|secret|token|api[_-]?(?:key|token)|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization|cookie|private[_-]?key|signature|credential)\\b\\s*[=:]\\s*)([^\\s,;]+)"
    )
    private val authorizationPattern = Regex("(?i)(\\bauthorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+")
    private val urlPasswordPattern = Regex("(?i)(://[^:/\\s]+:)[^@/\\s]+@")

    fun readLatest(): JournalReadResult {
        val process = ProcessBuilder(
            "/usr/bin/journalctl",
            "--unit=$JOURNAL_UNIT",
            "--output=json",
            "--no-pager",
            "--lines=${MAX_ENTRIES + 1}"
        ).redirectErrorStream(true).start()

        val outputFuture = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputFuture.cancel(true)
            throw IllegalStateException("journalctl timed out")
        }
        val output = outputFuture.get(2, TimeUnit.SECONDS)
        if (process.exitValue() != 0 ||
            output.contains("No journal files were opened", ignoreCase = true) ||
            output.contains("currently not seeing messages from other users and the system", ignoreCase = true)
        ) {
            throw IllegalStateException("journalctl could not read the Gatekeeperd system journal")
        }

        val parsed = output.lineSequence().mapNotNull(::parseRecord).toList()
        val truncated = parsed.size > MAX_ENTRIES
        return JournalReadResult(parsed.takeLast(MAX_ENTRIES).sortedByDescending { it.timestamp }, truncated)
    }

    internal fun parseRecord(line: String): AdminLogEntry? = runCatching {
        val record = json.parseToJsonElement(line).jsonObject
        val rawMessage = record.string("MESSAGE") ?: return null
        val structured = runCatching { json.parseToJsonElement(rawMessage).jsonObject }.getOrNull()
        val rawText = structured?.string("message") ?: rawMessage
        val safeMessage = redact(rawText)
        val rawStack = structured?.string("stack_trace")?.let(::redact)
        val level = structured?.string("level")?.uppercase()
            ?: priorityLevel(record.string("PRIORITY"))
        val timestamp = structured?.string("@timestamp") ?: structured?.string("timestamp")
            ?: record.string("__REALTIME_TIMESTAMP")?.toLongOrNull()?.let { micros ->
                Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000).toString()
            }
            ?: Instant.EPOCH.toString()
        val cursor = record.string("__CURSOR") ?: "$timestamp:${safeMessage.hashCode()}"

        AdminLogEntry(
            id = cursor,
            timestamp = timestamp,
            level = level,
            logger = structured?.string("logger_name")?.let(::redact),
            thread = structured?.string("thread_name")?.let(::redact),
            message = safeMessage.take(8_000),
            requestId = structured?.string("requestId")?.let(::redact),
            stackTrace = rawStack?.take(16_000)
        )
    }.getOrNull()

    internal fun summary(entries: List<AdminLogEntry>): AdminLogSummary {
        val successful = entries.count { requestStatus(it)?.let { code -> code in 200..399 } == true }
        val failed = entries.count { requestStatus(it)?.let { code -> code >= 400 } == true }
        val requests = successful + failed
        return AdminLogSummary(
            scanned = entries.size,
            errorLogs = entries.count { it.level.equals("ERROR", true) || it.level.equals("FATAL", true) },
            warningLogs = entries.count { it.level.equals("WARN", true) || it.level.equals("WARNING", true) },
            requestCount = requests,
            successfulRequests = successful,
            failedRequests = failed,
            successRate = requests.takeIf { it > 0 }?.let { successful * 100.0 / it },
            errorRate = requests.takeIf { it > 0 }?.let { failed * 100.0 / it }
        )
    }

    internal fun matches(entry: AdminLogEntry, search: String?, level: String?): Boolean {
        if (level != null && !entry.level.equals(level, ignoreCase = true)) return false
        if (search.isNullOrBlank()) return true
        val query = search.trim()
        return sequenceOf(entry.timestamp, entry.level, entry.logger, entry.thread, entry.message, entry.requestId, entry.stackTrace)
            .filterNotNull()
            .any { it.contains(query, ignoreCase = true) }
    }

    private fun requestStatus(entry: AdminLogEntry): Int? {
        if (entry.logger != "io.ktor.server.Application") return null
        val plainMessage = ansi.replace(entry.message, "")
        return statusCode.find(plainMessage)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun redact(value: String): String {
        val noAnsi = ansi.replace(value, "")
        return noAnsi
            .replace(authorizationPattern, "\$1[REDACTED]")
            .replace(secretPattern, "\$1[REDACTED]")
            .replace(urlPasswordPattern, "\$1[REDACTED]@")
    }

    private fun priorityLevel(priority: String?): String = when (priority?.toIntOrNull()) {
        0, 1, 2, 3 -> "ERROR"
        4 -> "WARN"
        5, 6 -> "INFO"
        7 -> "DEBUG"
        else -> "INFO"
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}

fun Application.configureLogAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
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
                    call.respondError(HttpStatusCode.BadRequest, "invalid_level", "level must be TRACE, DEBUG, INFO, WARN, ERROR, or FATAL")
                    return@get
                }

                val limit = rawLimit ?: 25
                val offset = rawOffset ?: 0
                val search = query["search"]?.trim()?.take(200)?.takeIf(String::isNotEmpty)
                val read = try {
                    withContext(Dispatchers.IO) { JournalLogReader.readLatest() }
                } catch (error: Exception) {
                    logAdminLogger.error("Unable to read Gatekeeperd system journal", error)
                    call.respondError(
                        HttpStatusCode.ServiceUnavailable,
                        "logs_unavailable",
                        "System logs are unavailable. Ensure the Gatekeeperd service account can read the system journal."
                    )
                    return@get
                }

                val filtered = read.entries.filter { JournalLogReader.matches(it, search, requestedLevel) }
                val page = filtered.drop(offset).take(limit)
                call.respond(
                    AdminLogsPage(
                        logs = page,
                        total = filtered.size,
                        limit = limit,
                        offset = offset,
                        hasMore = offset + page.size < filtered.size,
                        truncated = read.truncated,
                        summary = JournalLogReader.summary(read.entries)
                    )
                )
            }
        }
    }
}
