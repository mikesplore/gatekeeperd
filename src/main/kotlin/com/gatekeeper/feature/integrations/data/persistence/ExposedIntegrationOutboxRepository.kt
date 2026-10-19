package com.gatekeeper.feature.integrations.data.persistence

import com.gatekeeper.db.tables.IntegrationOutbox
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

class ExposedIntegrationOutboxRepository : IntegrationOutboxRepository {
    override fun summary(): IntegrationOutboxRepository.Summary = transaction {
        val rows = IntegrationOutbox.selectAll().toList()
        IntegrationOutboxRepository.Summary(
            rows.count { it[IntegrationOutbox.status] == "pending" }.toLong(),
            rows.count { it[IntegrationOutbox.status] == "processing" }.toLong(),
            rows.count { it[IntegrationOutbox.status] == "dead_letter" }.toLong(),
            rows.count { it[IntegrationOutbox.status] == "delivered" }.toLong()
        )
    }

    override fun enqueue(eventType: String, idempotencyKey: String, payload: String) {
        transaction {
            IntegrationOutbox.insertIgnore {
                it[IntegrationOutbox.destination] = "scribed"
                it[IntegrationOutbox.eventType] = eventType
                it[IntegrationOutbox.idempotencyKey] = idempotencyKey
                it[IntegrationOutbox.payload] = payload
            }
            IntegrationOutbox.update({
                (IntegrationOutbox.idempotencyKey eq idempotencyKey) and
                    IntegrationOutbox.deliveredAt.isNull() and
                    (IntegrationOutbox.status neq "processing")
            }) {
                it[IntegrationOutbox.status] = "pending"
                it[IntegrationOutbox.attempts] = 0
                it[IntegrationOutbox.createdAt] = LocalDateTime.now()
                it[IntegrationOutbox.nextAttemptAt] = LocalDateTime.now()
                it[IntegrationOutbox.lastError] = null
                it[IntegrationOutbox.leaseUntil] = null
            }
        }
    }

    override fun claim(limit: Int): List<IntegrationOutboxRepository.Event> = transaction {
        val now = LocalDateTime.now()
        val lease = now.plusMinutes(5)
        IntegrationOutbox.selectAll().where {
            IntegrationOutbox.deliveredAt.isNull() and
                ((IntegrationOutbox.status eq "pending") or ((IntegrationOutbox.status eq "processing") and (IntegrationOutbox.leaseUntil lessEq now))) and
                (IntegrationOutbox.nextAttemptAt lessEq now)
        }.orderBy(IntegrationOutbox.createdAt).limit(limit).mapNotNull { row ->
            val id = row[IntegrationOutbox.id]
            val updated = IntegrationOutbox.update({ (IntegrationOutbox.id eq id) and (IntegrationOutbox.deliveredAt.isNull()) }) {
                it[IntegrationOutbox.status] = "processing"
                it[IntegrationOutbox.leaseUntil] = lease
            }
            if (updated == 1) row.toEvent().copy(status = "pending", lastError = null) else null
        }
    }

    override fun pending(limit: Int): List<IntegrationOutboxRepository.Event> = transaction {
        IntegrationOutbox.selectAll().where { IntegrationOutbox.deliveredAt.isNull() }
            .orderBy(IntegrationOutbox.createdAt).limit(limit).map { it.toEvent() }
    }

    override fun markDelivered(id: UUID) { transaction {
        IntegrationOutbox.update({ IntegrationOutbox.id eq id }) {
            it[IntegrationOutbox.deliveredAt] = LocalDateTime.now()
            it[IntegrationOutbox.status] = "delivered"
            it[IntegrationOutbox.leaseUntil] = null
        }
    } }

    override fun markProjectEventsDelivered(projectId: UUID) { transaction {
        IntegrationOutbox.update({
            IntegrationOutbox.deliveredAt.isNull() and
                (IntegrationOutbox.eventType inList listOf("ledger", "payment")) and
                (IntegrationOutbox.payload like "%\"project_id\":\"$projectId\"%")
        }) {
            it[IntegrationOutbox.deliveredAt] = LocalDateTime.now()
            it[IntegrationOutbox.status] = "delivered"
            it[IntegrationOutbox.leaseUntil] = null
        }
    } }

    override fun markFailed(id: UUID, error: String, attempts: Int) { transaction {
        val delayMinutes = (1L shl attempts.coerceAtMost(8)).coerceAtMost(240)
        IntegrationOutbox.update({ IntegrationOutbox.id eq id }) {
            it[IntegrationOutbox.attempts] = attempts + 1
            it[IntegrationOutbox.lastError] = error.take(2000)
            it[IntegrationOutbox.nextAttemptAt] = LocalDateTime.now().plusMinutes(delayMinutes)
            it[IntegrationOutbox.status] = if (attempts + 1 >= 12) "dead_letter" else "pending"
            it[IntegrationOutbox.leaseUntil] = null
        }
    } }

    override fun replayAndClaim(id: UUID): IntegrationOutboxRepository.Event? = transaction {
        val now = LocalDateTime.now()
        val lease = now.plusMinutes(5)
        val updated = IntegrationOutbox.update({ (IntegrationOutbox.id eq id) and (IntegrationOutbox.deliveredAt.isNull()) }) {
            it[IntegrationOutbox.status] = "processing"
            it[IntegrationOutbox.nextAttemptAt] = now
            it[IntegrationOutbox.leaseUntil] = lease
        }
        if (updated != 1) return@transaction null
        IntegrationOutbox.selectAll().where { IntegrationOutbox.id eq id }.singleOrNull()?.toEvent()?.copy(status = "pending", lastError = null)
    }

    private fun ResultRow.toEvent() = IntegrationOutboxRepository.Event(
        this[IntegrationOutbox.id],
        this[IntegrationOutbox.eventType],
        this[IntegrationOutbox.idempotencyKey],
        this[IntegrationOutbox.payload],
        this[IntegrationOutbox.attempts],
        this[IntegrationOutbox.status],
        this[IntegrationOutbox.lastError]
    )
}
