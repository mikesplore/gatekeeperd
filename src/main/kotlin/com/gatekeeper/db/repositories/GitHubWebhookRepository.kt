package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.GitHubWebhookDeliveries
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

object GitHubWebhookRepository {
    fun claim(deliveryId: String, event: String, repository: String?): Boolean = transaction {
        val before = GitHubWebhookDeliveries.selectAll().where { GitHubWebhookDeliveries.deliveryId eq deliveryId }.count()
        GitHubWebhookDeliveries.insertIgnore {
            it[GitHubWebhookDeliveries.deliveryId] = deliveryId
            it[GitHubWebhookDeliveries.event] = event
            it[GitHubWebhookDeliveries.repository] = repository
        }
        before == 0L
    }

    fun setQueuedCount(deliveryId: String, count: Int) = transaction {
        GitHubWebhookDeliveries.update({ GitHubWebhookDeliveries.deliveryId eq deliveryId }) {
            it[queuedCount] = count
        }
    }
}
