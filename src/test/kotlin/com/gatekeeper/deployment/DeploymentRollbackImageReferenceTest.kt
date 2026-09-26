package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentRollbackImageReferenceTest {
    @Test
    fun `uses full repository digest returned by Docker unchanged`() {
        assertEquals(
            "localhost:5001/team/app@sha256:abc123",
            rollbackImageReference("localhost:5001", "team/app", "localhost:5001/team/app@sha256:abc123", "v1")
        )
    }

    @Test
    fun `qualifies a bare digest with configured registry and image name`() {
        assertEquals(
            "localhost:5001/team/app@sha256:abc123",
            rollbackImageReference("localhost:5001", "team/app", "sha256:abc123", "v1")
        )
    }

    @Test
    fun `falls back to the recorded tag when digest is absent`() {
        assertEquals(
            "team/app:v1",
            rollbackImageReference("docker.io", "team/app", null, "v1")
        )
    }
}
