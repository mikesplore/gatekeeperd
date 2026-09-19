package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object IntegrationOutbox : Table("integration_outbox") {
    val id = uuid("id").autoGenerate()
    val destination = text("destination")
    val eventType = text("event_type")
    val idempotencyKey = text("idempotency_key").uniqueIndex()
    val payload = text("payload")
    val attempts = integer("attempts").default(0)
    val status = text("status").default("pending")
    val leaseUntil = datetime("lease_until").nullable()
    val nextAttemptAt = datetime("next_attempt_at").defaultExpression(CurrentDateTime)
    val lastError = text("last_error").nullable()
    val deliveredAt = datetime("delivered_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
