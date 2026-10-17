package com.gatekeeper.nginx

import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.core.ports.ProcessRunner
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NginxCommandRunnerTest {
    @Test
    fun `nginx test preserves command output and exit status`() {
        val runner = NginxCommandRunner(FakeProcessRunner(ProcessResult(1, "", "configuration error")))

        val result = runner.test()

        assertFalse(result.valid)
        assertEquals(1, result.exitCode)
        assertEquals("configuration error", result.output)
    }

    @Test
    fun `reload reports command success`() {
        assertTrue(NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "", ""))).reload())
        assertFalse(NginxCommandRunner(FakeProcessRunner(ProcessResult(1, "", "failed"))).reload())
    }

    @Test
    fun `configuration dump is returned only for successful command`() {
        assertEquals("http {}", NginxCommandRunner(FakeProcessRunner(ProcessResult(0, "http {}", ""))).dumpConfig())
        assertEquals(null, NginxCommandRunner(FakeProcessRunner(ProcessResult(1, "", "failed"))).dumpConfig())
    }

    private class FakeProcessRunner(private val result: ProcessResult) : ProcessRunner {
        override fun run(command: List<String>, workingDirectory: Path?, environment: Map<String, String>, stdin: String?): ProcessResult = result
    }
}
