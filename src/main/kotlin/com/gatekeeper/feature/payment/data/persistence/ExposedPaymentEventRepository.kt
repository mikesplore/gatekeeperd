package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.PaymentEventRepository as LegacyPaymentEventRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import com.gatekeeper.feature.payment.domain.model.PaymentEvent
import com.gatekeeper.feature.payment.domain.model.PaymentEventReplay
import java.util.UUID

class ExposedPaymentEventRepository : PaymentEventRepository {
    override fun alreadyRecorded(dedupeKey: String) = LegacyPaymentEventRepository.alreadyRecorded(dedupeKey)

    override fun recordIfNew(
        dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, reference: String?, provider: String
    ) = LegacyPaymentEventRepository.recordIfNew(dedupeKey, eventType, rawPayload, projectId, paymentId, reference, provider)

    override fun markProcessed(id: UUID) = LegacyPaymentEventRepository.markProcessed(id)
    override fun markFailed(id: UUID, error: String) = LegacyPaymentEventRepository.markFailed(id, error)

    override fun findByStatus(status: String?, limit: Int, offset: Int, provider: String?): Pair<List<PaymentEvent>, Long> {
        val rows = LegacyPaymentEventRepository.findByStatus(status, limit, offset, provider)
        return rows.map {
            PaymentEvent(it.id, it.provider, it.reference, it.eventType, it.receivedAt, it.dedupeKey, it.processingStatus,
                it.processingAttempts, it.processingError, it.processedAt)
        } to LegacyPaymentEventRepository.countByStatus(status, provider)
    }

    override fun findForReplay(id: UUID): PaymentEventReplay? = LegacyPaymentEventRepository.findForReplay(id)?.let {
        PaymentEventReplay(it.id, it.rawPayload, it.processingStatus)
    }
}
