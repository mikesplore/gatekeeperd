package com.gatekeeper.deployment

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeploymentReadinessTest {
    @Test
    fun `readiness returns first successful probe without waiting again`() {
        var now = 0L
        var probes = 0
        var pauses = 0

        val result = DeploymentReadiness.await(
            timeoutNanos = 10,
            intervalMillis = 100,
            nowNanos = { now },
            checkCancelled = {},
            probe = { if (++probes == 2) "ready" else null },
            pause = { now++; pauses++ }
        )

        assertEquals("ready", result)
        assertEquals(2, probes)
        assertEquals(1, pauses)
    }

    @Test
    fun `readiness timeout uses controlled time and returns unavailable`() {
        var now = 0L
        var probes = 0
        val pauses = mutableListOf<Long>()

        val result = DeploymentReadiness.await(
            timeoutNanos = 3,
            intervalMillis = 250,
            nowNanos = { now },
            checkCancelled = {},
            probe = { probes++; null },
            pause = { interval -> pauses += interval; now++ }
        )

        assertEquals(null, result)
        assertEquals(3, probes)
        assertEquals(listOf(250L, 250L, 250L), pauses)
    }

    @Test
    fun `cancellation is checked before probing and is propagated`() {
        var probes = 0
        val cancelled = CancellationException("deployment cancelled")

        val thrown = assertFailsWith<CancellationException> {
            DeploymentReadiness.await(
                timeoutNanos = 10,
                intervalMillis = 100,
                nowNanos = { 0 },
                checkCancelled = { throw cancelled },
                probe = { probes++; "ready" },
                pause = { error("must not wait after cancellation") }
            )
        }

        assertEquals(cancelled, thrown)
        assertEquals(0, probes)
    }
}
