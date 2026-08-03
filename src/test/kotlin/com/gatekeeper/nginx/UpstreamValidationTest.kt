package com.gatekeeper.nginx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpstreamValidationTest {
    @Test
    fun `extractConfiguredPort parses trailing port`() {
        assertEquals(9921, extractConfiguredPort("acw-api:9921"))
        assertEquals(443, extractConfiguredPort("svc:443"))
        assertNull(extractConfiguredPort("acw-api"))
        assertNull(extractConfiguredPort("acw-api:not-a-port"))
    }

//    @Test
//    fun `extractConfiguredContainerName strips :port when present`() {
//        assertEquals("acw-api", extractConfiguredContainerName("acw-api:9921"))
//        assertEquals("acw-api", extractConfiguredContainerName("  acw-api:9921  "))
//        assertEquals("acw-api", extractConfiguredContainerName("acw-api"))
//        assertNull(extractConfiguredContainerName("   "))
//        assertNull(extractConfiguredContainerName(":9921"))
//    }

    @Test
    fun `parsePublishedHostPorts extracts host ports from docker ports string`() {
        assertEquals(setOf(9921), parsePublishedHostPorts("9921->9921/tcp"))
        assertEquals(setOf(80, 443), parsePublishedHostPorts("8080->80/tcp, 8443->443/tcp"))
        assertEquals(emptySet(), parsePublishedHostPorts(""))
        assertEquals(emptySet(), parsePublishedHostPorts("n/a"))
    }
}

