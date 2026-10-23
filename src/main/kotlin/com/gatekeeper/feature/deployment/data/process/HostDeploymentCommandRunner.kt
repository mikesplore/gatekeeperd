package com.gatekeeper.feature.deployment.data.process

import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.core.ports.ProcessTimeoutException
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

class HostDeploymentCommandRunner(
    private val processRunner: ProcessRunner = ProcessRunnerImpl()
) : DeploymentCommandRunner {
    override fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String>,
        stdin: String?,
        checkCancelled: () -> Unit,
        onOutput: (String) -> Unit
    ) {
        try {
            val result = processRunner.runStreaming(
                command = command,
                workingDirectory = directory,
                environment = environment,
                stdin = stdin,
                timeoutMillis = Long.MAX_VALUE,
                onOutput = onOutput,
                checkCancelled = checkCancelled
            )
            if (result.exitCode != 0) error("Command failed: ${command.first()}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    override fun output(command: List<String>, directory: Path): String {
        val result = processRunner.run(command, directory)
        if (result.exitCode != 0) error("Unable to read checked-out commit")
        return result.stdout + result.stderr
    }
}
