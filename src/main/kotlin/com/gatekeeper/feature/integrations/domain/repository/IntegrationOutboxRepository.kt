package com.gatekeeper.feature.integrations.domain.repository

import java.util.UUID

interface IntegrationOutboxRepository {
    data class Event(
        val id: UUID,
        val eventType: String,
        val idempotencyKey: String,
        val payload: String,
        val attempts: Int,
        val status: String = "pending",
        val lastError: String? = null
    )

    data class Summary(val pending: Long, val processing: Long, val deadLetter: Long, val delivered: Long)

    fun summary(): Summary
    fun enqueue(eventType: String, idempotencyKey: String, payload: String)
    fun claim(limit: Int = 50): List<Event>
    fun pending(limit: Int = 100): List<Event>
    fun markDelivered(id: UUID)
    fun markProjectEventsDelivered(projectId: UUID)
    fun markFailed(id: UUID, error: String, attempts: Int)
    fun replayAndClaim(id: UUID): Event?
}
