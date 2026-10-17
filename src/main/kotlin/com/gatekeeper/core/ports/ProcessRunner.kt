package com.gatekeeper.core.ports

import java.nio.file.Path

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ProcessRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null
    ): ProcessResult
}
