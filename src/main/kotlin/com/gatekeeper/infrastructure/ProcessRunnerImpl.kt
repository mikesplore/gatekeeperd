package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.core.ports.ProcessTimeoutException
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
            process.outputStream.bufferedWriter().use { writer -> if (stdin != null) writer.write(stdin) }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), stdout, stderr)
}
    override fun runStreaming(
        command: List<String>,
        workingDirectory: Path?,
        environment: Map<String, String>,
        stdin: String?,
        timeoutMillis: Long,
        onOutput: (String) -> Unit,
        checkCancelled: () -> Unit
    ): ProcessResult {
        require(command.isNotEmpty()) { "Process command must not be empty" }
        require(timeoutMillis > 0) { "Process timeout must be positive" }
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(true)
        if (workingDirectory != null) processBuilder.directory(workingDirectory.toFile())
        processBuilder.environment().putAll(environment)
        val process = processBuilder.start()
        val output = StringBuilder()
        val readFailure = AtomicReference<Throwable?>()
        val outputThread = Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        onOutput(line)
                    }
                }
            } catch (failure: Throwable) {
                readFailure.set(failure)
            }
        }, "process-runner-output").apply { isDaemon = true; start() }
        val startedAt = System.nanoTime()
        try {
            if (stdin != null) process.outputStream.bufferedWriter().use { it.write(stdin) }
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                checkCancelled()
                if (timeoutMillis != Long.MAX_VALUE &&
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= timeoutMillis
                ) throw ProcessTimeoutException(command)
            }
            outputThread.join()
            readFailure.get()?.let { throw it }
            checkCancelled()
            return ProcessResult(process.exitValue(), output.toString(), "")
        } catch (failure: Throwable) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            outputThread.join(1_000)
            if (failure is CancellationException) throw failure
            throw failure
        }
    }
}
