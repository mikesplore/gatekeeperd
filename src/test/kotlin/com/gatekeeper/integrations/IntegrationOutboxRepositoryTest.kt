package com.gatekeeper.integrations

import com.gatekeeper.db.repositories.IntegrationOutboxRepository
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
        DatabaseFactory.init(url, user, password)
        try {
            val key = "test-outbox-${UUID.randomUUID()}"
            IntegrationOutboxRepository.enqueue("payment", key, "{}")
            IntegrationOutboxRepository.enqueue("payment", key, "different")
            val first = IntegrationOutboxRepository.claim(10).first { it.idempotencyKey == key }
            assertEquals("{}", first.payload)
            IntegrationOutboxRepository.markFailed(first.id, "test failure", 11)
            assertTrue(IntegrationOutboxRepository.pending(100).any { it.id == first.id })
            val replayed = IntegrationOutboxRepository.replayAndClaim(first.id)
            assertTrue(replayed?.id == first.id)
            IntegrationOutboxRepository.markDelivered(first.id)
        } finally {
            DatabaseFactory.close()
        }
    }
}
