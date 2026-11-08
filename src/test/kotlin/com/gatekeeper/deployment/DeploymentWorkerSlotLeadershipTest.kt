package com.gatekeeper.deployment

import com.gatekeeper.config.AppSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentWorkerSlotLeadershipTest {
    @Test
    fun `only worker whose port is in active upstream is enabled`() {
        val upstream = Files.createTempFile("gatekeeperd-active-upstream", ".conf")
        try {
            Files.writeString(
                upstream,
                "upstream gatekeeperd_backend {\n    server 127.0.0.1:18081;\n}\n"
            )

            assertTrue(
                DeploymentWorkerSlotLeadership(
                    AppSettings.DeploymentWorkerSettings("blue", 18081, upstream.toFile())
                ).isActive()
            )
            assertFalse(
                DeploymentWorkerSlotLeadership(
                    AppSettings.DeploymentWorkerSettings("green", 18082, upstream.toFile())
                ).isActive()
            )
        } finally {
            Files.deleteIfExists(upstream)
        }
    }

    @Test
    fun `slot worker fails closed when active upstream cannot be read`() {
        val missing = Files.createTempDirectory("gatekeeperd-missing-upstream").resolve("missing.conf")

        assertFalse(
            DeploymentWorkerSlotLeadership(
                AppSettings.DeploymentWorkerSettings("green", 18082, missing.toFile())
            ).isActive()
        )
    }

    @Test
    fun `standalone deployment worker remains enabled without slot identity`() {
        val missing = Files.createTempDirectory("gatekeeperd-standalone-upstream").resolve("missing.conf")

        assertTrue(
            DeploymentWorkerSlotLeadership(
                AppSettings.DeploymentWorkerSettings(null, 8080, missing.toFile())
            ).isActive()
        )
    }
}
