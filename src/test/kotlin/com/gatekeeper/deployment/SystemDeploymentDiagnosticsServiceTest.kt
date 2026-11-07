package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.time.Instant

class SystemDeploymentDiagnosticsServiceTest {
    @Test
    fun `latest failed attempt includes redacted bounded log tail`() {
        val directory = Files.createTempDirectory("deployment-diagnostics")
        val id = "20261107T120000Z-12345-67bb36076d62"
        Files.writeString(
            directory.resolve("attempt-$id.meta"),
            """
                id=$id
                runId=12345
                commit=67bb36076d627e3bbab0f25945d941bc817e96e8
                status=failed
                phase=deployment
                exitCode=1
                startedAt=${Instant.parse("2026-11-07T12:00:00Z")}
                finishedAt=${Instant.parse("2026-11-07T12:10:00Z")}
            """.trimIndent()
        )
        Files.writeString(directory.resolve("attempt-$id.log"), "Authorization: Bearer private-token\nNginx worker drain timed out\n")

        val result = SystemDeploymentDiagnosticsService(directory).latest()

        assertTrue(result.available)
        assertEquals("failed", result.status)
        assertTrue(result.errorLog.contains("Nginx worker drain timed out"))
        assertFalse(result.errorLog.contains("private-token"))
    }

    @Test
    fun `missing attempt is reported as unavailable`() {
        val directory = Files.createTempDirectory("deployment-diagnostics-empty")

        val result = SystemDeploymentDiagnosticsService(directory).latest()

        assertFalse(result.available)
        assertEquals("", result.errorLog)
    }
}
