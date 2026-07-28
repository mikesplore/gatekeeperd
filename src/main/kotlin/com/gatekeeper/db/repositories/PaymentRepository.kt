package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.Payments
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

object PaymentRepository {

    data class PaymentRecord(
        val id: UUID,
        val projectId: UUID,
        val paystackReference: String,
        val authorizationUrl: String?,
        val amount: BigDecimal,
        val status: String,
        val paidAt: LocalDateTime?,
        val rawWebhookPayload: String?,
        val createdAt: LocalDateTime
    )

    fun findByReference(reference: String): PaymentRecord? {
        return transaction {
            Payments.selectAll().where { Payments.paystackReference eq reference }
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

    fun findLatestPendingAuthorizationUrl(projectId: UUID): String? {
        return transaction {
            Payments.selectAll()
                .where { (Payments.projectId eq projectId) and (Payments.status eq "pending") }
                .orderBy(Payments.createdAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.get(Payments.authorizationUrl)
                ?.takeIf { it.isNotBlank() }
        }
    }

    fun create(
        projectId: UUID,
        paystackReference: String,
        authorizationUrl: String?,
        amount: BigDecimal,
        status: String,
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
                it[Payments.rawWebhookPayload] = rawWebhookPayload
            }
            findByReference(paystackReference)!!
        }
    }

    fun markSuccess(reference: String, paidAt: LocalDateTime) {
        transaction {
            Payments.update({ Payments.paystackReference eq reference }) {
                it[Payments.status] = "success"
                it[Payments.paidAt] = paidAt
            }
        }
    }

    private fun ResultRow.toPaymentRecord() = PaymentRecord(
        id = this[Payments.id],
        projectId = this[Payments.projectId],
        paystackReference = this[Payments.paystackReference],
        authorizationUrl = this[Payments.authorizationUrl],
        amount = this[Payments.amount],
        status = this[Payments.status],
        paidAt = this[Payments.paidAt],
        rawWebhookPayload = this[Payments.rawWebhookPayload],
        createdAt = this[Payments.createdAt]
    )
}