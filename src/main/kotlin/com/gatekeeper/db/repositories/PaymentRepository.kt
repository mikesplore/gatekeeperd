package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.Payments
import com.gatekeeper.db.tables.Projects
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import com.gatekeeper.payments.PaymentProvider

object PaymentRepository {

    data class PaymentRecord(
        val id: UUID,
        val projectId: UUID,
        val provider: PaymentProvider,
        val providerReference: String,
        val paystackReference: String,
        val authorizationUrl: String?,
        val amount: BigDecimal,
        val status: String,
        val gatewayStatus: String,
        val verifiedVia: String?,
        val verifiedAt: LocalDateTime?,
        val paidAt: LocalDateTime?,
        val rawWebhookPayload: String?,
        val createdAt: LocalDateTime
    )

    data class PaymentWithProject(
        val payment: PaymentRecord,
        val projectName: String,
        val projectSlug: String
    )

    data class RevenueMonth(
        val month: String,
        val amount: BigDecimal
    )

    fun findByReference(reference: String): PaymentRecord? {
        return transaction {
            Payments.selectAll().where { Payments.paystackReference eq reference }
                .singleOrNull()
                ?.toPaymentRecord()
        }
    }

    fun findByProviderReference(provider: PaymentProvider, reference: String): PaymentRecord? = transaction {
        Payments.selectAll().where {
            (Payments.provider eq provider.name.lowercase()) and (Payments.providerReference eq reference)
        }.singleOrNull()?.toPaymentRecord()
    } ?: findByReference(reference)

    fun findById(id: UUID): PaymentRecord? {
        return transaction {
            Payments.selectAll().where { Payments.id eq id }
                .singleOrNull()
                ?.toPaymentRecord()
        }
    }

    fun findByProjectId(projectId: UUID): List<PaymentRecord> {
        return transaction {
            Payments.selectAll()
                .where { Payments.projectId eq projectId }
                .orderBy(Payments.createdAt, SortOrder.DESC)
                .map { it.toPaymentRecord() }
        }
    }

    fun findByProjectIdPage(projectId: UUID, limit: Int, offset: Int): Pair<List<PaymentRecord>, Long> = transaction {
        val query = Payments.selectAll().where { Payments.projectId eq projectId }
        query.orderBy(Payments.createdAt, SortOrder.DESC).limit(limit, offset.toLong()).map { it.toPaymentRecord() } to query.count()
    }

    fun successfulAmountForProject(projectId: UUID): BigDecimal = transaction {
        Payments.select(Payments.amount)
            .where { (Payments.projectId eq projectId) and (Payments.gatewayStatus eq "success") }
            .fold(BigDecimal.ZERO) { total, row -> total + row[Payments.amount] }
    }

    fun pendingAmountForProject(projectId: UUID): BigDecimal = transaction {
        Payments.select(Payments.amount)
            .where { (Payments.projectId eq projectId) and (Payments.gatewayStatus eq "pending") }
            .fold(BigDecimal.ZERO) { total, row -> total + row[Payments.amount] }
    }

    fun findLatestPendingAuthorizationUrl(projectId: UUID): String? {
        return transaction {
            Payments.selectAll()
                .where { (Payments.projectId eq projectId) and (Payments.gatewayStatus eq "pending") }
                .orderBy(Payments.createdAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(Payments.authorizationUrl)
                ?.takeIf { it.isNotBlank() }
        }
    }

    fun findPendingOlderThan(minutes: Long): List<PaymentRecord> {
        val cutoff = LocalDateTime.now().minusMinutes(minutes)
        return transaction {
            Payments.selectAll()
                .where { (Payments.gatewayStatus eq "pending") and (Payments.createdAt lessEq cutoff) }
                .orderBy(Payments.createdAt)
                .map { it.toPaymentRecord() }
        }
    }

    fun findAllFiltered(
        gatewayStatus: String?,
        projectSlug: String?,
        from: LocalDate?,
        until: LocalDate?,
        limit: Int,
        pageOffset: Int
    ): Pair<List<PaymentWithProject>, Long> {
        return transaction {
            val query = (Payments innerJoin Projects).selectAll()

            gatewayStatus?.let { query.andWhere { Payments.gatewayStatus eq it } }
            projectSlug?.let { query.andWhere { Projects.slug eq it } }
            if (from != null) {
                query.andWhere {
                    Coalesce(Payments.paidAt, Payments.createdAt) greaterEq from.atStartOfDay()
                }
            }
            if (until != null) {
                query.andWhere {
                    Coalesce(Payments.paidAt, Payments.createdAt) lessEq until.atTime(23, 59, 59)
                }
            }

            val total = query.count()

            val rows = query
                .orderBy(Payments.createdAt, SortOrder.DESC)
                .limit(limit, pageOffset.toLong())
                .map { row ->
                    PaymentWithProject(
                        payment = row.toPaymentRecord(),
                        projectName = row[Projects.name],
                        projectSlug = row[Projects.slug]
                    )
                }

            rows to total
        }
    }

    fun revenueByMonth(months: Int): List<RevenueMonth> {
        return transaction {
            val sql = """
                SELECT to_char(date_trunc('month', paid_at), 'YYYY-MM') AS month,
                       COALESCE(SUM(amount), 0) AS total
                FROM payments
                WHERE gateway_status = 'success' AND paid_at IS NOT NULL
                  AND paid_at >= date_trunc('month', CURRENT_DATE) - ($months - 1) * INTERVAL '1 month'
                GROUP BY 1
                ORDER BY 1
            """.trimIndent()
            exec(sql) { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RevenueMonth(
                                month = rs.getString("month"),
                                amount = rs.getBigDecimal("total")
                            )
                        )
                    }
                }
            } ?: emptyList()
        }
    }

    fun revenueTotals(): Pair<BigDecimal, BigDecimal> {
        return transaction {
            val sql = """
                SELECT
                    COALESCE(SUM(CASE WHEN paid_at >= date_trunc('month', CURRENT_DATE) THEN amount END), 0) AS this_month,
                    COALESCE(SUM(CASE WHEN paid_at >= date_trunc('month', CURRENT_DATE) - INTERVAL '1 month'
                                      AND paid_at < date_trunc('month', CURRENT_DATE) THEN amount END), 0) AS last_month
                FROM payments
                WHERE gateway_status = 'success' AND paid_at IS NOT NULL
            """.trimIndent()
            exec(sql) { rs ->
                if (rs.next()) {
                    rs.getBigDecimal("this_month") to rs.getBigDecimal("last_month")
                } else {
                    BigDecimal.ZERO to BigDecimal.ZERO
                }
            } ?: (BigDecimal.ZERO to BigDecimal.ZERO)
        }
    }

    fun create(
        projectId: UUID,
        paystackReference: String,
        authorizationUrl: String?,
        amount: BigDecimal,
        status: String,
        gatewayStatus: String = status,
        rawWebhookPayload: String? = null
    ): PaymentRecord {
        return transaction {
            val id = UUID.randomUUID()
            Payments.insert {
                it[Payments.id] = id
                it[Payments.projectId] = projectId
                it[Payments.paystackReference] = paystackReference
                it[Payments.authorizationUrl] = authorizationUrl
                it[Payments.amount] = amount
                it[Payments.status] = status
                it[Payments.gatewayStatus] = gatewayStatus
                it[Payments.rawWebhookPayload] = rawWebhookPayload
            }
            findByReference(paystackReference)!!
        }
    }

    fun create(
        projectId: UUID,
        provider: PaymentProvider,
        providerReference: String,
        authorizationUrl: String?,
        amount: BigDecimal,
        status: String,
        gatewayStatus: String = status,
        rawWebhookPayload: String? = null
    ): PaymentRecord = transaction {
        val id = UUID.randomUUID()
        Payments.insert {
            it[Payments.id] = id
            it[Payments.projectId] = projectId
            it[Payments.provider] = provider.name.lowercase()
            it[Payments.providerReference] = providerReference
            it[Payments.paystackReference] = providerReference
            it[Payments.authorizationUrl] = authorizationUrl
            it[Payments.amount] = amount
            it[Payments.status] = status
            it[Payments.gatewayStatus] = gatewayStatus
            it[Payments.rawWebhookPayload] = rawWebhookPayload
        }
        findByReference(providerReference)!!
    }

    fun markGatewayStatusByProviderReference(
        provider: PaymentProvider,
        reference: String,
        gatewayStatus: String,
        verifiedVia: String,
        paidAt: LocalDateTime? = null
    ) {
        transaction {
            Payments.update({
                (Payments.provider eq provider.name.lowercase()) and (Payments.providerReference eq reference)
            }) {
                it[Payments.status] = gatewayStatus
                it[Payments.gatewayStatus] = gatewayStatus
                it[Payments.verifiedVia] = verifiedVia
                it[Payments.verifiedAt] = LocalDateTime.now()
                if (paidAt != null) it[Payments.paidAt] = paidAt
            }
        }
    }

    fun updateAmountByProviderReference(provider: PaymentProvider, reference: String, amount: BigDecimal) {
        transaction {
            Payments.update({
                (Payments.provider eq provider.name.lowercase()) and (Payments.providerReference eq reference)
            }) { it[Payments.amount] = amount }
        }
    }

    fun markGatewayStatus(
        reference: String,
        gatewayStatus: String,
        verifiedVia: String,
        paidAt: LocalDateTime? = null
    ) {
        transaction {
            Payments.update({ Payments.paystackReference eq reference }) {
                it[Payments.status] = gatewayStatus
                it[Payments.gatewayStatus] = gatewayStatus
                it[Payments.verifiedVia] = verifiedVia
                it[Payments.verifiedAt] = LocalDateTime.now()
                if (paidAt != null) {
                    it[Payments.paidAt] = paidAt
                }
            }
        }
    }

    fun markGatewayStatusById(
        id: UUID,
        gatewayStatus: String,
        verifiedVia: String,
        paidAt: LocalDateTime? = null
    ) {
        transaction {
            Payments.update({ Payments.id eq id }) {
                it[Payments.status] = gatewayStatus
                it[Payments.gatewayStatus] = gatewayStatus
                it[Payments.verifiedVia] = verifiedVia
                it[Payments.verifiedAt] = LocalDateTime.now()
                if (paidAt != null) {
                    it[Payments.paidAt] = paidAt
                }
            }
        }
    }

    @Deprecated("Use markGatewayStatus", ReplaceWith("markGatewayStatus(reference, \"success\", verifiedVia, paidAt)"))
    fun markSuccess(reference: String, paidAt: LocalDateTime) {
        markGatewayStatus(reference, "success", "webhook", paidAt)
    }

    private fun ResultRow.toPaymentRecord() = PaymentRecord(
        id = this[Payments.id],
        projectId = this[Payments.projectId],
        provider = PaymentProvider.valueOf(this[Payments.provider].uppercase()),
        providerReference = this[Payments.providerReference] ?: this[Payments.paystackReference],
        paystackReference = this[Payments.paystackReference],
        authorizationUrl = this[Payments.authorizationUrl],
        amount = this[Payments.amount],
        status = this[Payments.status],
        gatewayStatus = this[Payments.gatewayStatus],
        verifiedVia = this[Payments.verifiedVia],
        verifiedAt = this[Payments.verifiedAt],
        paidAt = this[Payments.paidAt],
        rawWebhookPayload = this[Payments.rawWebhookPayload],
        createdAt = this[Payments.createdAt]
    )
}
