package com.gatekeeper.infrastructure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfrastructurePortsTest {
    @Test
    fun `port probe rejects invalid ports`() {
        val probe = TcpPortProbe()
        assertTrue(runCatching { probe.isReachable("127.0.0.1", 0, 10) }.isFailure)
    }

    @Test
    fun `port probe reports an unused port as unreachable`() {
        assertFalse(TcpPortProbe().isReachable("127.0.0.1", 65534, 25))
    }
}
