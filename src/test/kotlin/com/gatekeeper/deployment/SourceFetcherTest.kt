package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import com.gatekeeper.feature.deployment.domain.port.GitHubTokenProvider
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceFetcherTest {
    @Test
    fun `authenticated clone failure resets workspace and retries publicly`() {
        val environments = mutableListOf<Map<String, String>>()
        val outputs = mutableListOf<String>()
        var runCount = 0
        val commands = object : DeploymentCommandRunner {
            override fun run(
                command: List<String>, directory: Path, environment: Map<String, String>, stdin: String?,
                checkCancelled: () -> Unit, onOutput: (String) -> Unit
            ) {
                environments += environment
                runCount++
                onOutput("clone-output")
                if (runCount == 1) error("authentication rejected")
            }

            override fun output(command: List<String>, directory: Path) = "revision-sha\n"
        }
        val fetcher = SourceFetcher(commands, GitHubTokenProvider { "private-token" })
        var resetCount = 0

        val revision = kotlinx.coroutines.runBlocking {
            fetcher.fetch(
                repository = "owner/repository",
                gitRef = "main",
                workspace = Path.of("/tmp/workspace"),
                resetWorkspace = { resetCount++ },
                checkCancelled = {},
                onLog = {},
                onOutput = { line, token -> outputs += "$line:$token" }
            )
        }

        assertEquals("revision-sha", revision)
        assertEquals(2, runCount)
        assertEquals(1, resetCount)
        assertTrue(environments.first().getValue("GIT_CONFIG_VALUE_0").contains("private-token"))
        assertTrue(environments.last().isEmpty())
        assertEquals(listOf("clone-output:private-token", "clone-output:"), outputs)
    }
}
