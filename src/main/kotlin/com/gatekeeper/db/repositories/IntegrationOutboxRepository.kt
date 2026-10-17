package com.gatekeeper.db.repositories

import com.gatekeeper.feature.integrations.data.persistence.ExposedIntegrationOutboxRepository
import java.util.UUID

/** @deprecated Inject feature.integrations.domain.repository.IntegrationOutboxRepository. */
@Deprecated("Inject IntegrationOutboxRepository")
object IntegrationOutboxRepository {
    typealias Event = com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository.Event
    typealias Summary = com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository.Summary

    private val delegate = ExposedIntegrationOutboxRepository()
    fun summary() = delegate.summary()
    fun enqueue(eventType: String, idempotencyKey: String, payload: String) = delegate.enqueue(eventType, idempotencyKey, payload)
    fun claim(limit: Int = 50) = delegate.claim(limit)
    fun pending(limit: Int = 100) = delegate.pending(limit)
    fun markDelivered(id: UUID) = delegate.markDelivered(id)
    fun markFailed(id: UUID, error: String, attempts: Int) = delegate.markFailed(id, error, attempts)
    fun replayAndClaim(id: UUID) = delegate.replayAndClaim(id)
}
