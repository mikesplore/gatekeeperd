package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.data.process.HostDeploymentCommandRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostDeploymentCommandRunnerTest {
    private val runner = HostDeploymentCommandRunner()

    @Test
    fun `run streams merged command output and passes environment and stdin`() {
        val lines = mutableListOf<String>()
        runner.run(
            command = listOf("sh", "-c", "read value; printf '%s:%s\\n' \"\$value\" \"\$DEPLOYMENT_RUNNER_TEST\"; printf 'stderr-line\\n' >&2"),
            directory = Path.of("."),
            environment = mapOf("DEPLOYMENT_RUNNER_TEST" to "env-value"),
            stdin = "input-value\n",
            checkCancelled = {},
            onOutput = lines::add
        )

        assertEquals(setOf("input-value:env-value", "stderr-line"), lines.toSet())
    }

    @Test
    fun `run fails when command exits unsuccessfully`() {
        val error = assertFailsWith<IllegalStateException> {
            runner.run(listOf("sh", "-c", "exit 7"), Path.of("."), checkCancelled = {})
        }

        assertEquals("Command failed: sh", error.message)
    }

    @Test
    fun `output returns command stdout`() {
        assertEquals("revision", runner.output(listOf("sh", "-c", "printf revision"), Path.of(".")))
    }
}
