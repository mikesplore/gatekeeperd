package com.gatekeeper.feature.integrations.domain.repository

interface GitHubWebhookStore {
    fun claim(deliveryId: String, event: String, repository: String?): Boolean
    fun setQueuedCount(deliveryId: String, count: Int)
}
