package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import java.nio.file.Path

/** Runs the host Docker CLI steps used to produce or fetch a deployment image. */
class ImageBuilder(private val commands: DeploymentCommandRunner) {
    fun authenticate(
        registry: String,
        username: String,
        password: String,
        checkCancelled: () -> Unit
    ) {
        checkCancelled()
        val host = if (registry == "docker.io") "https://index.docker.io/v1/" else registry
        commands.run(
            command = listOf("docker", "login", host, "--username", username, "--password-stdin"),
            directory = Path.of("."),
            stdin = "$password\n",
            checkCancelled = checkCancelled
        )
        checkCancelled()
    }

    fun build(image: String, workspace: Path, checkCancelled: () -> Unit, onOutput: (String) -> Unit) {
        run(listOf("docker", "build", "--tag", image, workspace.toString()), workspace, checkCancelled, onOutput)
    }

    fun push(image: String, workspace: Path, checkCancelled: () -> Unit, onOutput: (String) -> Unit) {
        run(listOf("docker", "push", image), workspace, checkCancelled, onOutput)
    }

    fun pull(image: String, workspace: Path, checkCancelled: () -> Unit, onOutput: (String) -> Unit) {
        run(listOf("docker", "pull", image), workspace, checkCancelled, onOutput)
    }

    private fun run(command: List<String>, directory: Path, checkCancelled: () -> Unit, onOutput: (String) -> Unit) {
        commands.run(command, directory, checkCancelled = checkCancelled, onOutput = onOutput)
    }
}
