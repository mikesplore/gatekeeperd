package com.gatekeeper.deployment

import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.core.ports.ProcessRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentTriggerServiceTest {
    @Test
    fun `starts the systemd deployment checker without waiting`() {
        val runner = RecordingProcessRunner(ProcessResult(0, "", ""))

        val result = DeploymentTriggerService(runner).trigger()

        assertTrue(result.started)
        assertEquals(
            listOf("sudo", "-n", "/bin/systemctl", "start", "--no-block", "gatekeeperd-auto-deploy.service"),
            runner.command
        )
    }

    @Test
    fun `returns service error when trigger fails`() {
        val result = DeploymentTriggerService(
            RecordingProcessRunner(ProcessResult(1, "", "sudo: permission denied"))
        ).trigger()

        assertEquals(false, result.started)
        assertEquals("sudo: permission denied", result.error)
    }

    private class RecordingProcessRunner(
        private val response: ProcessResult,
    ) : ProcessRunner {
        var command: List<String> = emptyList()

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            environment: Map<String, String>,
            stdin: String?,
        ): ProcessResult {
            this.command = command
            return response
        }
    }
}
