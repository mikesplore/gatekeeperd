package com.gatekeeper.deployment

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPortListenersTest {
    @Test
    fun `reads only listening tcp ports from proc style input`() {
        val proc = Files.createTempFile("proc-net-tcp", ".txt").toFile()
        proc.writeText("""  sl  local_address rem_address   st
           0: 0100007F:7D00 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1
           1: 0100007F:7D01 00000000:0000 01 00000000:00000000 00:00000000 00000000 0 0 2
        """.trimIndent())
        assertEquals(setOf(32000), HostPortListeners.read(listOf(proc)))
        proc.delete()
    }

    @Test
    fun `port range overlap detects inclusive boundaries`() {
        assertTrue(overlaps(32000..33000, 33000..40000))
        assertFalse(overlaps(32000..32999, 33000..40000))
    }
}
