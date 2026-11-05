package com.gatekeeper.deployment

import com.gatekeeper.docker.DockerContainerDiagnostics
import com.gatekeeper.docker.HealthCheckDiagnostic
import kotlin.test.Test
import kotlin.test.assertContains

class CandidateDiagnosticsFormatterTest {
    @Test
    fun `formats docker exit and health details for failed candidates`() {
        val output = CandidateDiagnosticsFormatter.format(
            DockerContainerDiagnostics(
                state = "exited",
                restartCount = 3,
                exitCode = 137,
                oomKilled = true,
                error = "process killed",
                startedAt = "2026-11-05T10:00:00Z",
                finishedAt = "2026-11-05T10:00:02Z",
                healthStatus = "unhealthy",
                healthLog = listOf(HealthCheckDiagnostic(1, "start", "end", "health probe failed"))
            )
        )

        assertContains(output, "exitCode=137")
        assertContains(output, "oomKilled=true")
        assertContains(output, "dockerError=process killed")
        assertContains(output, "healthStatus=unhealthy")
        assertContains(output, "health probe failed")
    }

    @Test
    fun `explains when docker inspect details are unavailable`() {
        assertContains(
            CandidateDiagnosticsFormatter.format(null),
            "Docker inspect details were unavailable before candidate cleanup"
        )
    }
}
