package com.gatekeeper.nginx

import com.gatekeeper.feature.nginx.domain.service.UpstreamValidation
import com.gatekeeper.feature.nginx.infrastructure.solePublishedDockerTarget
import com.gatekeeper.feature.nginx.presentation.BackfillDockerTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpstreamValidationTest {
    private val validation = UpstreamValidation()

    @Test
    fun `extractConfiguredPort parses trailing port`() {
        assertEquals(9921, validation.extractConfiguredPort("acw-api:9921"))
        assertEquals(443, validation.extractConfiguredPort("svc:443"))
        assertNull(validation.extractConfiguredPort("acw-api"))
        assertNull(validation.extractConfiguredPort("acw-api:not-a-port"))
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
        assertEquals(setOf(9921), validation.parsePublishedHostPorts("9921->9921/tcp"))
        assertEquals(setOf(8080, 8443), validation.parsePublishedHostPorts("8080->80/tcp, 8443->443/tcp"))
        assertEquals(setOf(32768), validation.parsePublishedHostPorts("32768:9921/tcp"))
        assertEquals(setOf(32768, 8443), validation.parsePublishedHostPorts("0.0.0.0:32768->9921/tcp, 8443:443/tcp"))
        assertEquals(emptySet(), validation.parsePublishedHostPorts("9921/tcp"))
        assertEquals(emptySet(), validation.parsePublishedHostPorts(""))
        assertEquals(emptySet(), validation.parsePublishedHostPorts("n/a"))
    }

    @Test
    fun `parsePublishedPorts maps container ports to current host ports`() {
        assertEquals(
            mapOf(9921 to 32776, 443 to 8443),
            validation.parsePublishedPorts("0.0.0.0:32776->9921/tcp, 8443:443/tcp")
        )
        assertEquals(emptyMap(), validation.parsePublishedPorts("9921/tcp"))
    }

    @Test
    fun `backfill docker target requires exactly one published host port`() {
        assertEquals(BackfillDockerTarget("app", 9921), solePublishedDockerTarget("app", "9921->8080/tcp", validation))
        assertEquals(null, solePublishedDockerTarget("app", "9921->8080/tcp, 8443->443/tcp", validation))
        assertEquals(null, solePublishedDockerTarget("app", "", validation))
    }
}
