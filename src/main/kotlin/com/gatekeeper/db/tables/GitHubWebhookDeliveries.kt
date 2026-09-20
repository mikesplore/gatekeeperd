package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.CurrentDateTime

object GitHubWebhookDeliveries : Table("github_webhook_deliveries") {
    val deliveryId = text("delivery_id")
    val event = text("event")
    val repository = text("repository").nullable()
    val receivedAt = datetime("received_at").defaultExpression(CurrentDateTime)
    val queuedCount = integer("queued_count").default(0)
    override val primaryKey = PrimaryKey(deliveryId)
}
