package com.gatekeeper.feature.deployment.data.process

import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HostDeploymentCommandRunner : DeploymentCommandRunner {
    override fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        stdin: String?,
        checkCancelled: () -> Unit,
        onOutput: (String) -> Unit
    ) {
        require(command.isNotEmpty()) { "Command must not be empty" }
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        val outputThread = Thread({
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onOutput) }
        }, "deployment-command-output").apply { isDaemon = true; start() }
        try {
            if (stdin != null) process.outputStream.bufferedWriter().use { it.write(stdin) }
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) checkCancelled()
            outputThread.join()
            checkCancelled()
            if (process.exitValue() != 0) error("Command failed: ${command.first()}")
        } catch (cancelled: CancellationException) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            outputThread.join(1_000)
            throw cancelled
        }
    }

    override fun output(command: List<String>, directory: Path): String {
        require(command.isNotEmpty()) { "Command must not be empty" }
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) error("Unable to read checked-out commit")
        return output
    }
}
