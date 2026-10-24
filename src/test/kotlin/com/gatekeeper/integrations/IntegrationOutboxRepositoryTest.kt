package com.gatekeeper.integrations

import com.gatekeeper.feature.integrations.data.persistence.ExposedIntegrationOutboxRepository
import com.gatekeeper.plugins.DatabaseFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationOutboxRepositoryTest {
    @Test
    fun `outbox deduplicates claims dead letters and supports replay`() {
        val url = System.getenv("DB_URL") ?: return
        val user = System.getenv("DB_USER") ?: return
        val password = System.getenv("DB_PASSWORD") ?: return
        DatabaseFactory.init(url, user, password, "0")
        try {
            val outbox = ExposedIntegrationOutboxRepository()
            val key = "test-outbox-${UUID.randomUUID()}"
            outbox.enqueue("payment", key, "{}")
            outbox.enqueue("payment", key, "different")
            val first = outbox.claim(10).first { it.idempotencyKey == key }
            assertEquals("{}", first.payload)
            outbox.markFailed(first.id, "test failure", 11)
            assertTrue(outbox.pending(100).any { it.id == first.id })
            val replayed = outbox.replayAndClaim(first.id)
            assertEquals(replayed?.id, first.id)
            outbox.markDelivered(first.id)
        } finally {
            DatabaseFactory.close()
        }
    }
}
