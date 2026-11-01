package com.gatekeeper.admin

import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.core.ports.ProcessTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

data class LogEntry(
    val id: String,
    val timestamp: String,
    val level: String,
    val logger: String? = null,
    val thread: String? = null,
    val message: String,
    val requestId: String? = null,
    val stackTrace: String? = null
)

data class LogSummary(
    val scanned: Int,
    val errorLogs: Int,
    val warningLogs: Int,
    val requestCount: Int,
    val successfulRequests: Int,
    val failedRequests: Int,
    val successRate: Double?,
    val errorRate: Double?
)

data class LogQueryResult(
    val entries: List<LogEntry>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
    val truncated: Boolean,
    val summary: LogSummary
)

data class JournalReadResult(val entries: List<LogEntry>, val truncated: Boolean)

object JournalLogApplicationService {
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

    fun readLatest(processRunner: ProcessRunner): JournalReadResult {
        val result = try {
            processRunner.runStreaming(
                listOf("/usr/bin/journalctl", "--unit=$JOURNAL_UNIT", "--output=json", "--no-pager", "--lines=${MAX_ENTRIES + 1}"),
                timeoutMillis = 10_000
            )
        } catch (timeout: ProcessTimeoutException) {
            throw IllegalStateException("journalctl timed out", timeout)
        }
        val output = result.stdout + result.stderr
        if (result.exitCode != 0 ||
            output.contains("No journal files were opened", ignoreCase = true) ||
            output.contains("currently not seeing messages from other users and the system", ignoreCase = true)
        ) {
            throw IllegalStateException("journalctl could not read the Gatekeeperd system journal")
        }

        val parsed = output.lineSequence().mapNotNull(::parseRecord).toList()
        val truncated = parsed.size > MAX_ENTRIES
        return JournalReadResult(parsed.takeLast(MAX_ENTRIES).sortedByDescending { it.timestamp }, truncated)
    }

    internal fun parseRecord(line: String): LogEntry? = runCatching {
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

        LogEntry(
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

    internal fun summary(entries: List<LogEntry>): LogSummary {
        val successful = entries.count { requestStatus(it)?.let { code -> code in 200..399 } == true }
        val failed = entries.count { requestStatus(it)?.let { code -> code >= 400 } == true }
        val requests = successful + failed
        return LogSummary(
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

    suspend fun query(
        processRunner: ProcessRunner,
        search: String?,
        level: String?,
        limit: Int,
        offset: Int
    ): LogQueryResult {
        val read = withContext(Dispatchers.IO) { readLatest(processRunner) }
        val filtered = read.entries.filter { matches(it, search, level) }
        val page = filtered.drop(offset).take(limit)
        return LogQueryResult(
            entries = page,
            total = filtered.size,
            limit = limit,
            offset = offset,
            hasMore = offset + page.size < filtered.size,
            truncated = read.truncated,
            summary = summary(read.entries)
        )
    }

    internal fun matches(entry: LogEntry, search: String?, level: String?): Boolean {
        if (level != null && !entry.level.equals(level, ignoreCase = true)) return false
        if (search.isNullOrBlank()) return true
        val query = search.trim()
        return sequenceOf(
            entry.timestamp,
            entry.level,
            entry.logger,
            entry.thread,
            entry.message,
            entry.requestId,
            entry.stackTrace
        )
            .filterNotNull()
            .any { it.contains(query, ignoreCase = true) }
    }

    private fun requestStatus(entry: LogEntry): Int? {
        if (entry.logger != "io.ktor.server.Application") return null
        val plainMessage = ansi.replace(entry.message, "")
        return statusCode.find(plainMessage)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun redact(value: String): String {
        val noAnsi = ansi.replace(value, "")
        return noAnsi
            .replace(authorizationPattern, $$"$1[REDACTED]")
            .replace(secretPattern, $$"$1[REDACTED]")
            .replace(urlPasswordPattern, $$"$1[REDACTED]@")
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


/** Compatibility facade for log parsing behavior consumed by existing callers and tests. */
object JournalLogReader {
    fun parseRecord(line: String): AdminLogEntry? = JournalLogApplicationService.parseRecord(line)?.toAdminLogEntry()

    fun summary(entries: List<AdminLogEntry>): AdminLogSummary {
        val value = JournalLogApplicationService.summary(entries.map { it.toLogEntry() })
        return AdminLogSummary(
            scanned = value.scanned,
            errorLogs = value.errorLogs,
            warningLogs = value.warningLogs,
            requestCount = value.requestCount,
            successfulRequests = value.successfulRequests,
            failedRequests = value.failedRequests,
            successRate = value.successRate,
            errorRate = value.errorRate
        )
    }

    fun matches(entry: AdminLogEntry, search: String?, level: String?): Boolean =
        JournalLogApplicationService.matches(entry.toLogEntry(), search, level)
}

private fun LogEntry.toAdminLogEntry() =
    AdminLogEntry(id, timestamp, level, logger, thread, message, requestId, stackTrace)

private fun AdminLogEntry.toLogEntry() =
    LogEntry(id, timestamp, level, logger, thread, message, requestId, stackTrace)
