package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeExposureTest {
    @Test
    fun `only active adopted runtime wildcard binds are reported as public`() {
        assertTrue(adoptedRuntimeHasPublicBinding("container_adoption", mapOf(8080 to "0.0.0.0")))
        assertTrue(adoptedRuntimeHasPublicBinding("container_adoption", mapOf(8080 to "::")))
        assertFalse(adoptedRuntimeHasPublicBinding("container_adoption", mapOf(8080 to "127.0.0.1")))
        assertFalse(adoptedRuntimeHasPublicBinding("deployment", mapOf(8080 to "0.0.0.0")))
        assertFalse(adoptedRuntimeHasPublicBinding("container_adoption", null))
    }
}
