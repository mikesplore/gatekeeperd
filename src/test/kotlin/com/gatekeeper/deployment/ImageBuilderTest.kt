package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageBuilderTest {
    @Test
    fun `build push and pull delegate commands in order`() {
        val commands = RecordingCommandRunner()
        val builder = ImageBuilder(commands)
        val output = mutableListOf<String>()

        builder.build("registry.example/app:v1", Path.of("/tmp/work"), {}, output::add)
        builder.push("registry.example/app:v1", Path.of("/tmp/work"), {}, output::add)
        builder.pull("registry.example/app:v1", Path.of("/tmp/work"), {}, output::add)

        assertEquals(
            listOf(
                listOf("docker", "build", "--tag", "registry.example/app:v1", "/tmp/work"),
                listOf("docker", "push", "registry.example/app:v1"),
                listOf("docker", "pull", "registry.example/app:v1")
            ),
            commands.invocations.map { it.command }
        )
        assertEquals(listOf("command output", "command output", "command output"), output)
    }

    @Test
    fun `registry authentication sends password through stdin`() {
        val commands = RecordingCommandRunner()

        ImageBuilder(commands).authenticate("docker.io", "user", "secret", {})

        val call = commands.invocations.single()
        assertEquals(listOf("docker", "login", "https://index.docker.io/v1/", "--username", "user", "--password-stdin"), call.command)
        assertEquals("secret\n", call.stdin)
    }

    private class RecordingCommandRunner : DeploymentCommandRunner {
        val invocations = mutableListOf<Invocation>()

        override fun run(command: List<String>, directory: Path, environment: Map<String, String>, stdin: String?, checkCancelled: () -> Unit, onOutput: (String) -> Unit) {
            invocations += Invocation(command, stdin)
            onOutput("command output")
        }

        override fun output(command: List<String>, directory: Path): String = ""
    }

    private data class Invocation(val command: List<String>, val stdin: String?)
}
