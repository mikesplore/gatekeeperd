package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.PaymentEvents
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.*

object PaymentEventRepository {

    data class PaymentEventRecord(
        val id: UUID,
        val dedupeKey: String?,
        val eventType: String,
        val reference: String?,
        val processingStatus: String,
        val processingAttempts: Int,
        val processingError: String?,
        val receivedAt: LocalDateTime,
        val processedAt: LocalDateTime?
    )

    fun findByStatus(status: String?, limit: Int): List<PaymentEventRecord> {
        return transaction {
            val query = PaymentEvents.selectAll()
            status?.takeIf { it.isNotBlank() }?.let { value ->
                query.andWhere { PaymentEvents.processingStatus eq value }
            }
            query.orderBy(PaymentEvents.receivedAt, SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .map { row ->
                    PaymentEventRecord(
                        id = row[PaymentEvents.id],
                        dedupeKey = row[PaymentEvents.dedupeKey],
                        eventType = row[PaymentEvents.eventType],
                        reference = row[PaymentEvents.paystackReference],
                        processingStatus = row[PaymentEvents.processingStatus],
                        processingAttempts = row[PaymentEvents.processingAttempts],
                        processingError = row[PaymentEvents.processingError],
                        receivedAt = row[PaymentEvents.receivedAt],
                        processedAt = row[PaymentEvents.processedAt]
                    )
                }
        }
    }

    fun alreadyRecorded(dedupeKey: String): Boolean {
        return transaction {
            PaymentEvents.select(PaymentEvents.id)
                .where { PaymentEvents.dedupeKey eq dedupeKey }
                .limit(1)
                .count() > 0
        }
    }

    fun recordIfNew(
        dedupeKey: String,
        eventType: String,
        rawPayload: String,
        projectId: UUID?,
        paymentId: UUID?,
        paystackReference: String?
    ): UUID? {
        val id = UUID.randomUUID()
        return try {
            transaction {
                PaymentEvents.insert {
                    it[PaymentEvents.id] = id
                    it[PaymentEvents.dedupeKey] = dedupeKey
                    it[PaymentEvents.eventType] = eventType
                    it[PaymentEvents.rawPayload] = rawPayload
                    it[PaymentEvents.projectId] = projectId
                    it[PaymentEvents.paymentId] = paymentId
                    it[PaymentEvents.paystackReference] = paystackReference
                }
            }
            id
        } catch (_: Exception) {
            // A unique-key conflict means another request recorded this event first.
            null
        }
    }

    fun markProcessed(id: UUID) {
        transaction {
            val attempts = PaymentEvents.selectAll()
                .where { PaymentEvents.id eq id }
                .singleOrNull()
                ?.get(PaymentEvents.processingAttempts)
                ?: return@transaction
            PaymentEvents.update({ PaymentEvents.id eq id }) {
                it[PaymentEvents.processingStatus] = "processed"
                it[PaymentEvents.processingAttempts] = attempts + 1
                it[PaymentEvents.processingError] = null
                it[PaymentEvents.processedAt] = LocalDateTime.now()
            }
        }
    }

    fun markFailed(id: UUID, error: String) {
        transaction {
            val attempts = PaymentEvents.selectAll()
                .where { PaymentEvents.id eq id }
                .singleOrNull()
                ?.get(PaymentEvents.processingAttempts)
                ?: return@transaction
            PaymentEvents.update({ PaymentEvents.id eq id }) {
                it[PaymentEvents.processingStatus] = "failed"
                it[PaymentEvents.processingAttempts] = attempts + 1
                it[PaymentEvents.processingError] = error.take(2000)
            }
        }
    }
}
