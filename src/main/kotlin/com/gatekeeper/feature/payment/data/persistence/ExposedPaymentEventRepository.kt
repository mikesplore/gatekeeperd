package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.PaymentEventRepository as LegacyPaymentEventRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import java.util.UUID

class ExposedPaymentEventRepository : PaymentEventRepository {
    override fun alreadyRecorded(dedupeKey: String) = LegacyPaymentEventRepository.alreadyRecorded(dedupeKey)

    override fun recordIfNew(
        dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, reference: String?, provider: String
    ) = LegacyPaymentEventRepository.recordIfNew(dedupeKey, eventType, rawPayload, projectId, paymentId, reference, provider)

    override fun markProcessed(id: UUID) = LegacyPaymentEventRepository.markProcessed(id)
    override fun markFailed(id: UUID, error: String) = LegacyPaymentEventRepository.markFailed(id, error)
}
