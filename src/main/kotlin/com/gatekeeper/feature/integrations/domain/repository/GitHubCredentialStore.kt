package com.gatekeeper.feature.integrations.domain.repository

import java.util.UUID

interface GitHubCredentialStore {
    companion object {
        const val APP_PRIVATE_KEY = "app_private_key"
        const val WEBHOOK_SECRET = "webhook_secret"
    }

    data class Version(val id: UUID, val version: Int)
    fun privateKeyPem(): String?
    fun webhookSecret(): String?
    fun save(type: String, value: String): Version
}
