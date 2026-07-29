package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.PaymentEvents
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

object PaymentEventRepository {

    fun record(
        eventType: String,
        rawPayload: String,
        projectId: UUID?,
        paymentId: UUID?,
        paystackReference: String?
    ) {
        transaction {
            PaymentEvents.insert {
                it[PaymentEvents.eventType] = eventType
                it[PaymentEvents.rawPayload] = rawPayload
                it[PaymentEvents.projectId] = projectId
                it[PaymentEvents.paymentId] = paymentId
                it[PaymentEvents.paystackReference] = paystackReference
            }
        }
    }
}
