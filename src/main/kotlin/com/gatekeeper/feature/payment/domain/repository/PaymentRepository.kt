package com.gatekeeper.feature.payment.domain.repository

import com.gatekeeper.feature.payment.domain.model.Payment
import java.util.UUID

interface PaymentRepository {
    fun findByProviderReference(provider: String, reference: String): Payment?
    fun findById(id: UUID): Payment?
    fun findByReference(reference: String): Payment?
    fun save(payment: Payment): Payment
    fun create(projectId: UUID, provider: String, reference: String, amount: java.math.BigDecimal, status: String, rawPayload: String? = null): Payment
    fun updateStatus(provider: String, reference: String, status: String, verifiedVia: String, paidAt: java.time.LocalDateTime? = null, amount: java.math.BigDecimal? = null)
    fun pendingPayments(olderThanMinutes: Long): List<Payment>
}

interface PaymentEventRepository {
    fun alreadyRecorded(dedupeKey: String): Boolean
    fun recordIfNew(dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, reference: String?, provider: String): UUID?
    fun markProcessed(id: UUID)
    fun markFailed(id: UUID, error: String)
}
