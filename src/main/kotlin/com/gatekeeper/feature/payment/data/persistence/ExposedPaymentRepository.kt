package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.PaymentRepository as LegacyPaymentRepository
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.payments.PaymentProvider
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import java.time.LocalDate
import com.gatekeeper.feature.payment.domain.model.PaymentCounts
import com.gatekeeper.feature.payment.domain.model.PaymentRevenueMonth
import com.gatekeeper.feature.payment.domain.model.PaymentWithProject

/** Transitional adapter; legacy query/report consumers are migrated feature by feature. */
class ExposedPaymentRepository : PaymentRepository {
    override fun findByProviderReference(provider: String, reference: String): Payment? =
        LegacyPaymentRepository.findByProviderReference(PaymentProvider.valueOf(provider.uppercase()), reference)?.toDomain()

    override fun findById(id: UUID): Payment? = LegacyPaymentRepository.findById(id)?.toDomain()

    override fun findByReference(reference: String): Payment? = LegacyPaymentRepository.findByReference(reference)?.toDomain()

    override fun create(projectId: UUID, provider: String, reference: String, amount: BigDecimal, status: String, rawPayload: String?, authorizationUrl: String?): Payment =
        LegacyPaymentRepository.create(projectId, PaymentProvider.valueOf(provider.uppercase()), reference, authorizationUrl, amount, status, status, rawPayload).toDomain()
    override fun save(payment: Payment): Payment {
        val existing = findByProviderReference(payment.provider, payment.providerReference)
        if (existing == null) {
            LegacyPaymentRepository.create(
                payment.projectId,
                PaymentProvider.valueOf(payment.provider.uppercase()),
                payment.providerReference,
                null,
                payment.amount,
                payment.status,
                payment.status,
                payment.rawPayload
            )
        } else {
            updateStatus(payment.provider, payment.providerReference, payment.status, "payment_use_case", payment.paidAt, payment.amount)
        }
        return findByProviderReference(payment.provider, payment.providerReference)!!
    }

    override fun updateStatus(
        provider: String,
        reference: String,
        status: String,
        verifiedVia: String,
        paidAt: LocalDateTime?,
        amount: BigDecimal?
    ) {
        val providerValue = PaymentProvider.valueOf(provider.uppercase())
        if (amount != null) LegacyPaymentRepository.updateAmountByProviderReference(providerValue, reference, amount)
        LegacyPaymentRepository.markGatewayStatusByProviderReference(providerValue, reference, status, verifiedVia, paidAt)
    }

    override fun pendingPayments(olderThanMinutes: Long): List<Payment> =
        LegacyPaymentRepository.findPendingOlderThan(olderThanMinutes).map { it.toDomain() }

    override fun findByProjectId(projectId: UUID): List<Payment> = LegacyPaymentRepository.findByProjectId(projectId).map { it.toDomain() }

    override fun findByProjectIdPage(projectId: UUID, limit: Int, offset: Int): Pair<List<Payment>, Long> =
        LegacyPaymentRepository.findByProjectIdPage(projectId, limit, offset).let { (rows, total) -> rows.map { it.toDomain() } to total }

    override fun findAllFiltered(status: String?, projectSlug: String?, from: LocalDate?, until: LocalDate?, limit: Int, offset: Int): Pair<List<PaymentWithProject>, Long> =
        LegacyPaymentRepository.findAllFiltered(status, projectSlug, from, until, limit, offset).let { (rows, total) ->
            rows.map { PaymentWithProject(it.payment.toDomain(), it.projectName, it.projectSlug) } to total
        }

    override fun revenueTotals() = LegacyPaymentRepository.revenueTotals()
    override fun revenueByMonth(months: Int) = LegacyPaymentRepository.revenueByMonth(months).map { PaymentRevenueMonth(it.month, it.amount) }
    override fun paymentCounts() = LegacyPaymentRepository.paymentCounts().let { PaymentCounts(it.total, it.successful, it.pending, it.failed) }
    override fun latestPendingAuthorizationUrl(projectId: UUID) = LegacyPaymentRepository.findLatestPendingAuthorizationUrl(projectId)

    private fun LegacyPaymentRepository.PaymentRecord.toDomain() = Payment(
        id = id,
        projectId = projectId,
        provider = provider.name.lowercase(),
        reference = providerReference,
        providerReference = providerReference,
        amount = amount,
        status = gatewayStatus,
        paidAt = paidAt,
        recordStatus = status,
        authorizationUrl = authorizationUrl,
        verifiedVia = verifiedVia,
        createdAt = createdAt
    )
}
