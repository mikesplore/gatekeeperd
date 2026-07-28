package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Payments : Table("payments") {
    val id = uuid("id").autoGenerate()
    val projectId = uuid("project_id").references(Projects.id)
    val paystackReference = text("paystack_reference").uniqueIndex()
    val amount = decimal("amount", 12, 2)
    val status = text("status")
    val paidAt = datetime("paid_at").nullable()
    val rawWebhookPayload = text("raw_webhook_payload").nullable()
    val createdAt = datetime("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}