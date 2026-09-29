package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedPortsTest {
    @Test
    fun `published port mapping falls back to canonical host and container fields`() {
        assertEquals(mapOf(9002 to 32940), PublishedPorts.normalize(emptyMap(), 9002, 32940))
    }

    @Test
    fun `valid recorded mappings take precedence over fallback fields`() {
        assertEquals(mapOf(9002 to 32940), PublishedPorts.normalize(mapOf(9002 to 32940), 8080, 32768))
    }
}
