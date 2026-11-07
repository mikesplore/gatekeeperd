package com.gatekeeper.deployment

import kotlinx.serialization.Serializable
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties

class SystemDeploymentDiagnosticsService(
    private val directory: Path = Path.of("/opt/gatekeeperd/deployment-attempts"),
    private val maxLogBytes: Int = 64 * 1024,
) {
    fun latest(): SystemDeploymentDiagnostics {
        if (!Files.exists(directory)) return SystemDeploymentDiagnostics(available = false)
        val metadataPath = Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { it.fileName.toString().matches(METADATA_FILE) }
                .maxByOrNull { path -> Files.getLastModifiedTime(path).toMillis() }
        }
        if (metadataPath == null) return SystemDeploymentDiagnostics(available = false)

        val metadata = Properties().apply { Files.newInputStream(metadataPath).use(::load) }
        val id = metadata.getProperty("id")?.takeIf(ID::matches)
            ?: throw IllegalStateException("Deployment diagnostics metadata has an invalid attempt ID")
        val status = metadata.getProperty("status")?.takeIf { it in setOf("running", "succeeded", "failed") }
            ?: "unknown"
        val logPath = directory.resolve("attempt-$id.log").normalize()
        require(logPath.parent == directory.normalize()) { "Deployment diagnostics log path is invalid" }
        val logTail = if (status == "failed" && Files.isRegularFile(logPath)) readTail(logPath) else LogTail("", false)

        return SystemDeploymentDiagnostics(
            available = true,
            id = id,
            runId = metadata.getProperty("runId"),
            commit = metadata.getProperty("commit"),
            status = status,
            phase = metadata.getProperty("phase"),
            exitCode = metadata.getProperty("exitCode")?.toIntOrNull(),
            startedAt = metadata.getProperty("startedAt"),
            finishedAt = metadata.getProperty("finishedAt")?.takeIf(String::isNotBlank),
            errorLog = redact(logTail.text),
            logTruncated = logTail.truncated,
        )
    }

    private fun readTail(path: Path): LogTail = RandomAccessFile(path.toFile(), "r").use { file ->
        val length = file.length()
        val start = (length - maxLogBytes).coerceAtLeast(0)
        file.seek(start)
        val bytes = ByteArray((length - start).toInt())
        file.readFully(bytes)
        val text = String(bytes, StandardCharsets.UTF_8).let { value ->
            if (start > 0) value.substringAfter('\n', value) else value
        }
        LogTail(text, length > maxLogBytes)
    }

    private data class LogTail(val text: String, val truncated: Boolean)

    private fun redact(value: String): String = value
        .replace(ANSI, "")
        .replace(AUTHORIZATION) { "${it.groupValues[1]}[REDACTED]" }
        .replace(SENSITIVE_VALUE) { "${it.groupValues[1]}[REDACTED]" }
        .replace(URL_PASSWORD) { "${it.groupValues[1]}[REDACTED]@" }

    private companion object {
        val METADATA_FILE = Regex("attempt-[A-Za-z0-9T-]+\\.meta")
        val ID = Regex("[A-Za-z0-9T-]+")
        val ANSI = Regex("\\u001B\\[[;\\d]*m")
        val AUTHORIZATION = Regex("(?i)(authorization[ \\t]*[:=][ \\t]*)(?:(?:bearer|basic)[ \\t]+)?[^ \\t\\r\\n,;]+(?:[ \\t]+[^ \\t\\r\\n,;]+)?")
        val SENSITIVE_VALUE = Regex("(?i)((?:password|secret|token|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+")
        val URL_PASSWORD = Regex("(://[^:/\\s]+:)[^@/\\s]+(@)")
    }
}

@Serializable
data class SystemDeploymentDiagnostics(
    val available: Boolean,
    val id: String? = null,
    val runId: String? = null,
    val commit: String? = null,
    val status: String? = null,
    val phase: String? = null,
    val exitCode: Int? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val errorLog: String = "",
    val logTruncated: Boolean = false,
)
