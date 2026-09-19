package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.PaymentEvents
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object PaymentEventRepository {

    fun alreadyRecorded(dedupeKey: String): Boolean {
        return transaction {
            PaymentEvents.select(PaymentEvents.id)
                .where { PaymentEvents.dedupeKey eq dedupeKey }
                .limit(1)
                .count() > 0
        }
    }

    fun record(
        dedupeKey: String,
        eventType: String,
        rawPayload: String,
        projectId: UUID?,
        paymentId: UUID?,
        paystackReference: String?
    ) {
        transaction {
            PaymentEvents.insert {
                it[PaymentEvents.dedupeKey] = dedupeKey
                it[PaymentEvents.eventType] = eventType
                it[PaymentEvents.rawPayload] = rawPayload
                it[PaymentEvents.projectId] = projectId
                it[PaymentEvents.paymentId] = paymentId
                it[PaymentEvents.paystackReference] = paystackReference
            }
        }
    }
}
