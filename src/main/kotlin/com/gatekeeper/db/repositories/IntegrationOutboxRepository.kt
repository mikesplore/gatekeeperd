package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.IntegrationOutbox
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

object IntegrationOutboxRepository {
    data class Event(val id: UUID, val eventType: String, val idempotencyKey: String, val payload: String, val attempts: Int)
    data class Summary(val pending: Long, val processing: Long, val deadLetter: Long, val delivered: Long)

    fun summary(): Summary = transaction {
        val rows = IntegrationOutbox.selectAll().toList()
        Summary(rows.count { it[IntegrationOutbox.status] == "pending" }.toLong(), rows.count { it[IntegrationOutbox.status] == "processing" }.toLong(), rows.count { it[IntegrationOutbox.status] == "dead_letter" }.toLong(), rows.count { it[IntegrationOutbox.status] == "delivered" }.toLong())
    }

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

    fun claim(limit: Int = 50): List<Event> = transaction {
        val now = LocalDateTime.now()
        val lease = now.plusMinutes(5)
        IntegrationOutbox.selectAll().where {
            IntegrationOutbox.deliveredAt.isNull() and
                ((IntegrationOutbox.status eq "pending") or ((IntegrationOutbox.status eq "processing") and (IntegrationOutbox.leaseUntil lessEq now))) and
                (IntegrationOutbox.nextAttemptAt lessEq now)
        }.orderBy(IntegrationOutbox.createdAt).limit(limit).mapNotNull { row ->
            val id = row[IntegrationOutbox.id]
            val updated = IntegrationOutbox.update({ (IntegrationOutbox.id eq id) and (IntegrationOutbox.deliveredAt.isNull()) }) {
                it[IntegrationOutbox.status] = "processing"; it[IntegrationOutbox.leaseUntil] = lease
            }
            if (updated == 1) Event(id, row[IntegrationOutbox.eventType], row[IntegrationOutbox.idempotencyKey], row[IntegrationOutbox.payload], row[IntegrationOutbox.attempts]) else null
        }
    }

    fun pending(limit: Int = 100): List<Event> = transaction {
        IntegrationOutbox.selectAll().where { IntegrationOutbox.deliveredAt.isNull() }.orderBy(IntegrationOutbox.createdAt).limit(limit).map {
            Event(it[IntegrationOutbox.id], it[IntegrationOutbox.eventType], it[IntegrationOutbox.idempotencyKey], it[IntegrationOutbox.payload], it[IntegrationOutbox.attempts])
        }
    }

    fun markDelivered(id: UUID) = transaction {
        IntegrationOutbox.update({ IntegrationOutbox.id eq id }) { it[IntegrationOutbox.deliveredAt] = LocalDateTime.now(); it[IntegrationOutbox.status] = "delivered"; it[IntegrationOutbox.leaseUntil] = null }
    }

    fun markFailed(id: UUID, error: String, attempts: Int) = transaction {
        val delayMinutes = (1L shl attempts.coerceAtMost(8)).coerceAtMost(240)
        IntegrationOutbox.update({ IntegrationOutbox.id eq id }) {
            it[IntegrationOutbox.attempts] = attempts + 1
            it[IntegrationOutbox.lastError] = error.take(2000)
            it[IntegrationOutbox.nextAttemptAt] = LocalDateTime.now().plusMinutes(delayMinutes)
            it[IntegrationOutbox.status] = if (attempts + 1 >= 12) "dead_letter" else "pending"
            it[IntegrationOutbox.leaseUntil] = null
        }
    }

    fun replay(id: UUID) = transaction { IntegrationOutbox.update({ IntegrationOutbox.id eq id }) { it[IntegrationOutbox.status] = "pending"; it[IntegrationOutbox.nextAttemptAt] = LocalDateTime.now(); it[IntegrationOutbox.leaseUntil] = null } }
}
