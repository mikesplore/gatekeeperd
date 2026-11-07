package com.gatekeeper.deployment

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentContainerNamingTest {
    @Test
    fun `runtime names include readable service and environment`() {
        assertEquals(
            "weusifix-api-production-12345678",
            deploymentContainerName("Weusifix API", "production", UUID.fromString("12345678-1234-1234-1234-123456789abc"))
        )
    }

    @Test
    fun `runtime names remain valid and within docker name limit`() {
        val name = deploymentContainerName("A very long service name that exceeds the docker limit by quite a lot", "preview", UUID.fromString("abcdef12-1234-1234-1234-123456789abc"))
        assertTrue(name.length <= 63)
        assertTrue(name.matches(Regex("^[a-z0-9][a-z0-9_.-]*$")))
        assertTrue(name.startsWith("a-very-long-service-name"))
        assertTrue(name.contains("-preview-abcdef12"))
    }
}
