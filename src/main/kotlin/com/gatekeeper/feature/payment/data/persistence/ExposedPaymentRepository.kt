package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.tables.Payments
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.model.PaymentCounts
import com.gatekeeper.feature.payment.domain.model.PaymentRevenueMonth
import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class ExposedPaymentRepository : PaymentRepository {
    override fun findByProviderReference(provider: String, reference: String): Payment? = transaction {
        Payments.selectAll().where {
            (Payments.provider eq provider.lowercase()) and
                ((Payments.providerReference eq reference) or (Payments.paystackReference eq reference))
        }.singleOrNull()?.toDomain()
    } ?: findByReference(reference)

    override fun findById(id: UUID): Payment? = transaction {
        Payments.selectAll().where { Payments.id eq id }.singleOrNull()?.toDomain()
    }

    override fun findByReference(reference: String): Payment? = transaction {
        Payments.selectAll().where { Payments.paystackReference eq reference }.singleOrNull()?.toDomain()
    }

    override fun create(
        projectId: UUID,
        provider: String,
        reference: String,
        amount: BigDecimal,
        status: String,
        rawPayload: String?,
        authorizationUrl: String?,
        serviceId: UUID?
    ): Payment = transaction {
        val id = UUID.randomUUID()
        Payments.insert {
            it[Payments.id] = id
            it[Payments.projectId] = projectId
            it[Payments.serviceId] = serviceId
            it[Payments.provider] = provider.lowercase()
            it[Payments.providerReference] = reference
            it[Payments.paystackReference] = reference
            it[Payments.amount] = amount
            it[Payments.status] = status
            it[Payments.gatewayStatus] = status
            it[Payments.authorizationUrl] = authorizationUrl
            it[Payments.rawWebhookPayload] = rawPayload
        }
        findById(id)!!
    }

    override fun save(payment: Payment): Payment {
        val existing = findByProviderReference(payment.provider, payment.providerReference)
        if (existing == null) return create(
            payment.projectId, payment.provider, payment.providerReference, payment.amount,
            payment.status, payment.rawPayload, payment.authorizationUrl, payment.serviceId
        )
        updateStatus(payment.provider, payment.providerReference, payment.status, "payment_use_case", payment.paidAt, payment.amount)
        return findById(payment.id) ?: findByProviderReference(payment.provider, payment.providerReference)!!
    }

    override fun updateStatus(
        provider: String,
        reference: String,
        status: String,
        verifiedVia: String,
        paidAt: LocalDateTime?,
        amount: BigDecimal?
    ) {
        transaction {
            Payments.update({
                (Payments.provider eq provider.lowercase()) and
                    ((Payments.providerReference eq reference) or (Payments.paystackReference eq reference))
            }) {
                it[Payments.status] = status
                it[Payments.gatewayStatus] = status
                it[Payments.verifiedVia] = verifiedVia
                it[Payments.verifiedAt] = LocalDateTime.now()
                if (paidAt != null) it[Payments.paidAt] = paidAt
                if (amount != null) it[Payments.amount] = amount
            }
        }
    }

    override fun pendingPayments(olderThanMinutes: Long): List<Payment> {
        val cutoff = LocalDateTime.now().minusMinutes(olderThanMinutes)
        return transaction {
            Payments.selectAll().where { (Payments.gatewayStatus eq "pending") and (Payments.createdAt lessEq cutoff) }
                .orderBy(Payments.createdAt).map { it.toDomain() }
        }
    }

    override fun findPendingByProjectId(projectId: UUID): List<Payment> = transaction {
        Payments.selectAll().where { (Payments.projectId eq projectId) and (Payments.gatewayStatus eq "pending") }
            .orderBy(Payments.createdAt).map { it.toDomain() }
    }

    override fun findPendingByServiceId(serviceId: UUID): List<Payment> = transaction {
        Payments.selectAll().where { (Payments.serviceId eq serviceId) and (Payments.gatewayStatus eq "pending") }
            .orderBy(Payments.createdAt).map { it.toDomain() }
    }

    override fun successfulAmountForProject(projectId: UUID): BigDecimal = sumForProject(projectId, "success")
    override fun pendingAmountForProject(projectId: UUID): BigDecimal = sumForProject(projectId, "pending")
    override fun pendingAmountForServiceId(serviceId: UUID): BigDecimal = transaction {
        Payments.select(Payments.amount).where { (Payments.serviceId eq serviceId) and (Payments.gatewayStatus eq "pending") }
            .fold(BigDecimal.ZERO) { total, row -> total + row[Payments.amount] }
    }

    private fun sumForProject(projectId: UUID, status: String): BigDecimal = transaction {
        Payments.select(Payments.amount).where { (Payments.projectId eq projectId) and (Payments.gatewayStatus eq status) }
            .fold(BigDecimal.ZERO) { total, row -> total + row[Payments.amount] }
    }

    override fun findByProjectId(projectId: UUID): List<Payment> = transaction {
        Payments.selectAll().where { Payments.projectId eq projectId }
            .orderBy(Payments.createdAt, SortOrder.DESC).map { it.toDomain() }
    }

    override fun findByProjectIdPage(projectId: UUID, limit: Int, offset: Int): Pair<List<Payment>, Long> = transaction {
        val query = Payments.selectAll().where { Payments.projectId eq projectId }
        query.orderBy(Payments.createdAt, SortOrder.DESC)
            .limit(limit.coerceIn(1, 500), offset.coerceAtLeast(0).toLong())
            .map { it.toDomain() } to query.count()
    }

    override fun findAllFiltered(
        status: String?, projectSlug: String?, from: LocalDate?, until: LocalDate?, limit: Int, offset: Int
    ): Pair<List<PaymentWithProject>, Long> = transaction {
        val query = (Payments innerJoin Projects).selectAll()
        status?.takeIf { it.isNotBlank() }?.let { value -> query.andWhere { Payments.gatewayStatus eq value } }
        projectSlug?.takeIf { it.isNotBlank() }?.let { value -> query.andWhere { Projects.slug eq value } }
        from?.let { date -> query.andWhere { Coalesce(Payments.paidAt, Payments.createdAt) greaterEq date.atStartOfDay() } }
        until?.let { date -> query.andWhere { Coalesce(Payments.paidAt, Payments.createdAt) lessEq date.atTime(23, 59, 59) } }
        val total = query.count()
        val rows = query.orderBy(Payments.createdAt, SortOrder.DESC)
            .limit(limit.coerceIn(1, 500), offset.coerceAtLeast(0).toLong())
            .map { row -> PaymentWithProject(row.toDomain(), row[Projects.name], row[Projects.slug]) }
        rows to total
    }

    override fun revenueTotals(): Pair<BigDecimal, BigDecimal> = transaction {
        val sql = """
            SELECT
                COALESCE(SUM(CASE WHEN paid_at >= date_trunc('month', CURRENT_DATE) THEN amount END), 0) AS this_month,
                COALESCE(SUM(CASE WHEN paid_at >= date_trunc('month', CURRENT_DATE) - INTERVAL '1 month'
                                  AND paid_at < date_trunc('month', CURRENT_DATE) THEN amount END), 0) AS last_month
            FROM payments
            WHERE gateway_status = 'success' AND paid_at IS NOT NULL
        """.trimIndent()
        exec(sql) { rs -> if (rs.next()) rs.getBigDecimal("this_month") to rs.getBigDecimal("last_month") else BigDecimal.ZERO to BigDecimal.ZERO }
            ?: (BigDecimal.ZERO to BigDecimal.ZERO)
    }

    override fun revenueByMonth(months: Int): List<PaymentRevenueMonth> = transaction {
        val monthCount = months.coerceIn(1, 24)
        val sql = """
            SELECT to_char(months.month_start, 'YYYY-MM') AS month, COALESCE(SUM(payments.amount), 0) AS total
            FROM generate_series(
                date_trunc('month', CURRENT_DATE) - ($monthCount - 1) * INTERVAL '1 month',
                date_trunc('month', CURRENT_DATE), INTERVAL '1 month'
            ) AS months(month_start)
            LEFT JOIN payments ON payments.gateway_status = 'success'
                AND payments.paid_at >= months.month_start
                AND payments.paid_at < months.month_start + INTERVAL '1 month'
            GROUP BY months.month_start ORDER BY months.month_start
        """.trimIndent()
        exec(sql) { rs -> buildList { while (rs.next()) add(PaymentRevenueMonth(rs.getString("month"), rs.getBigDecimal("total"))) } }
            ?: emptyList()
    }

    override fun paymentCounts(): PaymentCounts = transaction {
        val sql = """
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE gateway_status = 'success') AS successful,
                   COUNT(*) FILTER (WHERE gateway_status = 'pending') AS pending,
                   COUNT(*) FILTER (WHERE gateway_status = 'failed') AS failed
            FROM payments
        """.trimIndent()
        exec(sql) { rs ->
            if (rs.next()) PaymentCounts(rs.getLong("total"), rs.getLong("successful"), rs.getLong("pending"), rs.getLong("failed"))
            else PaymentCounts(0, 0, 0, 0)
        } ?: PaymentCounts(0, 0, 0, 0)
    }

    override fun latestPendingAuthorizationUrl(projectId: UUID): String? = transaction {
        Payments.selectAll().where { (Payments.projectId eq projectId) and (Payments.gatewayStatus eq "pending") }
            .orderBy(Payments.createdAt, SortOrder.DESC).limit(1).singleOrNull()?.get(Payments.authorizationUrl)
    }

    private fun ResultRow.toDomain() = Payment(
        id = this[Payments.id], projectId = this[Payments.projectId],
        serviceId = this[Payments.serviceId],
        provider = this[Payments.provider], providerReference = this[Payments.providerReference] ?: this[Payments.paystackReference],
        reference = this[Payments.providerReference] ?: this[Payments.paystackReference],
        amount = this[Payments.amount], status = this[Payments.gatewayStatus], recordStatus = this[Payments.status],
        authorizationUrl = this[Payments.authorizationUrl], verifiedVia = this[Payments.verifiedVia],
        rawPayload = this[Payments.rawWebhookPayload], paidAt = this[Payments.paidAt], createdAt = this[Payments.createdAt]
    )
}
