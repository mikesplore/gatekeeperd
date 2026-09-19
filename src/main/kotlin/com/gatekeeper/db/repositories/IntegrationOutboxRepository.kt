package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.IntegrationOutbox
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

object IntegrationOutboxRepository {
    data class Event(val id: UUID, val eventType: String, val idempotencyKey: String, val payload: String, val attempts: Int)

    fun enqueue(eventType: String, idempotencyKey: String, payload: String) {
        transaction {
            IntegrationOutbox.insertIgnore {
                it[IntegrationOutbox.destination] = "scribed"
                it[IntegrationOutbox.eventType] = eventType
                it[IntegrationOutbox.idempotencyKey] = idempotencyKey
                it[IntegrationOutbox.payload] = payload
            }
        }
    }

    fun due(limit: Int = 50): List<Event> = transaction {
        IntegrationOutbox.selectAll().where {
            IntegrationOutbox.deliveredAt.isNull() and (IntegrationOutbox.nextAttemptAt lessEq LocalDateTime.now())
        }.orderBy(IntegrationOutbox.createdAt).limit(limit).map {
            Event(it[IntegrationOutbox.id], it[IntegrationOutbox.eventType], it[IntegrationOutbox.idempotencyKey], it[IntegrationOutbox.payload], it[IntegrationOutbox.attempts])
        }
    }

    fun markDelivered(id: UUID) = transaction {
        IntegrationOutbox.update({ IntegrationOutbox.id eq id }) { it[IntegrationOutbox.deliveredAt] = LocalDateTime.now() }
    }

    fun markFailed(id: UUID, error: String, attempts: Int) = transaction {
        val delayMinutes = (1L shl attempts.coerceAtMost(8)).coerceAtMost(240)
        IntegrationOutbox.update({ IntegrationOutbox.id eq id }) {
            it[IntegrationOutbox.attempts] = attempts + 1
            it[IntegrationOutbox.lastError] = error.take(2000)
            it[IntegrationOutbox.nextAttemptAt] = LocalDateTime.now().plusMinutes(delayMinutes)
        }
    }
}
