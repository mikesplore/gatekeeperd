package com.gatekeeper.deployment

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentCutoverRetryPolicyTest {
    private val now = LocalDateTime.parse("2026-10-14T12:00:00")

    @Test
    fun `ready candidate waits for retry cooldown after cutover failure`() {
        assertFalse(
            DeploymentCutoverRetryPolicy.isEligible(
                "readiness_succeeded", now.minusSeconds(DeploymentCutoverRetryPolicy.RETRY_DELAY_SECONDS - 1), now
            )
        )
        assertTrue(
            DeploymentCutoverRetryPolicy.isEligible(
                "readiness_succeeded", now.minusSeconds(DeploymentCutoverRetryPolicy.RETRY_DELAY_SECONDS), now
            )
        )
        assertTrue(
            DeploymentCutoverRetryPolicy.isEligible(
                "cutover_in_progress", now.minusSeconds(DeploymentCutoverRetryPolicy.RETRY_DELAY_SECONDS + 1), now
            )
        )
    }

    @Test
    fun `new deployment steps are not delayed by cutover cooldown`() {
        assertTrue(DeploymentCutoverRetryPolicy.isEligible("building", now, now))
        assertTrue(DeploymentCutoverRetryPolicy.isEligible("queued", now, now))
    }
}
