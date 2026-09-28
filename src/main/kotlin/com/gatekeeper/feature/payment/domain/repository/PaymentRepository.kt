package com.gatekeeper.feature.payment.domain.repository

import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.model.PaymentCounts
import com.gatekeeper.feature.payment.domain.model.PaymentRevenueMonth
import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

interface PaymentRepository {
    fun findByProviderReference(provider: String, reference: String): Payment?
    fun findById(id: UUID): Payment?
    fun findByReference(reference: String): Payment?
    fun save(payment: Payment): Payment
    fun create(projectId: UUID, provider: String, reference: String, amount: java.math.BigDecimal, status: String, rawPayload: String? = null, authorizationUrl: String? = null): Payment
    fun updateStatus(provider: String, reference: String, status: String, verifiedVia: String, paidAt: java.time.LocalDateTime? = null, amount: java.math.BigDecimal? = null)
    fun pendingPayments(olderThanMinutes: Long): List<Payment>
    fun findByProjectId(projectId: UUID): List<Payment>
    fun findByProjectIdPage(projectId: UUID, limit: Int, offset: Int): Pair<List<Payment>, Long>
    fun findAllFiltered(status: String?, projectSlug: String?, from: LocalDate?, until: LocalDate?, limit: Int, offset: Int): Pair<List<PaymentWithProject>, Long>
    fun revenueTotals(): Pair<BigDecimal, BigDecimal>
    fun revenueByMonth(months: Int): List<PaymentRevenueMonth>
    fun paymentCounts(): PaymentCounts
    fun latestPendingAuthorizationUrl(projectId: UUID): String?
}

interface PaymentEventRepository {
    fun alreadyRecorded(dedupeKey: String): Boolean
    fun recordIfNew(dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, reference: String?, provider: String): UUID?
    fun markProcessed(id: UUID)
    fun markFailed(id: UUID, error: String)
    fun findByStatus(status: String?, limit: Int, offset: Int, provider: String?): Pair<List<com.gatekeeper.feature.payment.domain.model.PaymentEvent>, Long>
    fun findForReplay(id: UUID): com.gatekeeper.feature.payment.domain.model.PaymentEventReplay?
}
