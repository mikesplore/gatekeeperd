package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.tables.PaymentEvents
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import com.gatekeeper.feature.payment.domain.model.PaymentEvent
import com.gatekeeper.feature.payment.domain.model.PaymentEventReplay
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

class ExposedPaymentEventRepository : PaymentEventRepository {
    override fun alreadyRecorded(dedupeKey: String): Boolean = transaction {
        PaymentEvents.select(PaymentEvents.id).where { PaymentEvents.dedupeKey eq dedupeKey }.limit(1).count() > 0
    }

    override fun recordIfNew(
        dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, reference: String?, provider: String
    ): UUID? = recordIfNew(dedupeKey, eventType, rawPayload, projectId, paymentId, null, reference, provider)

    override fun recordIfNew(
        dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, serviceId: UUID?, reference: String?, provider: String
    ): UUID? {
        val id = UUID.randomUUID()
        return try {
            transaction {
                PaymentEvents.insert {
                    it[PaymentEvents.id] = id
                    it[PaymentEvents.dedupeKey] = dedupeKey
                    it[PaymentEvents.eventType] = eventType
                    it[PaymentEvents.provider] = provider
                    it[PaymentEvents.rawPayload] = rawPayload
                    it[PaymentEvents.projectId] = projectId
                    it[PaymentEvents.serviceId] = serviceId
                    it[PaymentEvents.paymentId] = paymentId
                    it[PaymentEvents.paystackReference] = reference
                }
            }
            id
        } catch (_: Exception) {
            null
        }
    }

    override fun markProcessed(id: UUID) = transaction {
        val attempts = PaymentEvents.selectAll().where { PaymentEvents.id eq id }.singleOrNull()
            ?.get(PaymentEvents.processingAttempts) ?: return@transaction
        PaymentEvents.update({ PaymentEvents.id eq id }) {
            it[PaymentEvents.processingStatus] = "processed"
            it[PaymentEvents.processingAttempts] = attempts + 1
            it[PaymentEvents.processingError] = null
            it[PaymentEvents.processedAt] = LocalDateTime.now()
        }
    }

    override fun markFailed(id: UUID, error: String) = transaction {
        val attempts = PaymentEvents.selectAll().where { PaymentEvents.id eq id }.singleOrNull()
            ?.get(PaymentEvents.processingAttempts) ?: return@transaction
        PaymentEvents.update({ PaymentEvents.id eq id }) {
            it[PaymentEvents.processingStatus] = "failed"
            it[PaymentEvents.processingAttempts] = attempts + 1
            it[PaymentEvents.processingError] = error.take(2000)
        }
    }

    override fun findByStatus(status: String?, limit: Int, offset: Int, provider: String?): Pair<List<PaymentEvent>, Long> {
        return transaction {
            val query = PaymentEvents.selectAll()
            status?.takeIf { it.isNotBlank() }?.let { value -> query.andWhere { PaymentEvents.processingStatus eq value } }
            provider?.takeIf { it.isNotBlank() }?.let { value -> query.andWhere { PaymentEvents.provider eq value } }
            val total = query.count()
            val events = query.orderBy(PaymentEvents.receivedAt, SortOrder.DESC)
                .limit(limit.coerceIn(1, 500), offset.coerceAtLeast(0).toLong())
                .map { row ->
                    PaymentEvent(
                        row[PaymentEvents.id], row[PaymentEvents.provider], row[PaymentEvents.paystackReference],
                        row[PaymentEvents.eventType], row[PaymentEvents.receivedAt], row[PaymentEvents.dedupeKey],
                        row[PaymentEvents.processingStatus], row[PaymentEvents.processingAttempts],
                        row[PaymentEvents.processingError], row[PaymentEvents.processedAt], row[PaymentEvents.serviceId]
                    )
                }
            events to total
        }
    }

    override fun findForReplay(id: UUID): PaymentEventReplay? = transaction {
        PaymentEvents.selectAll().where { PaymentEvents.id eq id }.singleOrNull()?.let {
            PaymentEventReplay(it[PaymentEvents.id], it[PaymentEvents.rawPayload], it[PaymentEvents.processingStatus])
        }
    }
}
