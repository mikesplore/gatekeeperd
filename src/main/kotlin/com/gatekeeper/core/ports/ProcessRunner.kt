package com.gatekeeper.core.ports

import java.nio.file.Path

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

class ProcessTimeoutException(command: List<String>) :
    IllegalStateException("Command timed out: ${command.firstOrNull() ?: "<empty>"}")

interface ProcessRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null
    ): ProcessResult

    fun runStreaming(
        command: List<String>,
        workingDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null,
        timeoutMillis: Long,
        onOutput: (String) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ProcessResult {
        checkCancelled()
        val result = run(command, workingDirectory, environment, stdin)
        (result.stdout + result.stderr).lineSequence().forEach(onOutput)
        checkCancelled()
        return result
    }
}
