package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.core.ports.ProcessRunner
import java.nio.file.Path

class ProcessRunnerImpl : ProcessRunner {
    override fun run(
        command: List<String>,
        workingDirectory: Path?,
        environment: Map<String, String>,
        stdin: String?
    ): ProcessResult {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        val processBuilder = ProcessBuilder(command)
        if (workingDirectory != null) processBuilder.directory(workingDirectory.toFile())
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        if (stdin != null) process.outputStream.bufferedWriter().use { it.write(stdin) }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), stdout, stderr)
    }
}
