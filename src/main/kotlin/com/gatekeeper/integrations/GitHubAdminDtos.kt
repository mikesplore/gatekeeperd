package com.gatekeeper.integrations

import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class GitHubCredentialRequest(val value: String)

@Serializable
data class GitHubStatusResponse(
    val configured: Boolean,
    val connected: Boolean,
    val appId: Long? = null,
    val installationId: Long? = null,
    val accountLogin: String? = null,
    val accountType: String? = null,
    val appSlug: String? = null,
)

@Serializable
data class GitHubInstallUrlResponse(val url: String, val callbackUrl: String? = null)
