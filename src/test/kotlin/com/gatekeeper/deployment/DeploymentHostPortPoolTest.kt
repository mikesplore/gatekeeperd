package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeploymentHostPortPoolTest {
    @Test
    fun `candidate slot alternates around active slot`() {
        assertEquals(0, DeploymentHostPortPool.candidateSlot(null))
        assertEquals(1, DeploymentHostPortPool.candidateSlot(0))
        assertEquals(0, DeploymentHostPortPool.candidateSlot(1))
    }

    @Test
    fun `port selection skips persisted reservations and docker ports`() {
        assertEquals(
            32002,
            DeploymentHostPortPool.firstAvailable(
                32000..32002,
                reserved = setOf(32000),
                inUse = setOf(32001)
            )
        )
    }

    @Test
    fun `port selection reports exhausted range`() {
        assertNull(DeploymentHostPortPool.firstAvailable(32000..32001, setOf(32000), setOf(32001)))
    }
}
