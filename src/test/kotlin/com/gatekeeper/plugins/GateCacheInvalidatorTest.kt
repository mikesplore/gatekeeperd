package com.gatekeeper.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GateCacheInvalidatorTest {
    @Test
    fun `invalidation uses normalized site slugs and distinct keys`() {
        val deleted = mutableListOf<String>()
        GateCacheInvalidator(deleted::add).invalidate(listOf(" Service-A ", "service-a", "Service-B"))

        assertEquals(listOf("project:status:service-a", "project:status:service-b"), deleted)
    }

    @Test
    fun `invalidation failure is counted and does not stop other site keys`() {
        val before = Metrics.snapshot()["gate.cache_invalidation_failure"] ?: 0L
        val attempted = mutableListOf<String>()
        GateCacheInvalidator { key ->
            attempted += key
            if (key.endsWith("service-a")) error("redis unavailable")
        }.invalidate(listOf("service-a", "service-b"))

        assertEquals(listOf("project:status:service-a", "project:status:service-b"), attempted)
        assertTrue((Metrics.snapshot()["gate.cache_invalidation_failure"] ?: 0L) > before)
    }
}
