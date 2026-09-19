package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object PaymentEvents : Table("payment_events") {
    val id = uuid("id").autoGenerate()
    val dedupeKey = text("dedupe_key").nullable().uniqueIndex()
    val paymentId = uuid("payment_id").references(Payments.id).nullable()
    val projectId = uuid("project_id").references(Projects.id).nullable()
    val eventType = text("event_type")
    val paystackReference = text("paystack_reference").nullable()
    val rawPayload = text("raw_payload")
    val receivedAt = datetime("received_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
