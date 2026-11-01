package com.gatekeeper.integrations

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class GitHubWebhookResponse(val status: String, val event: String, val deploymentsQueued: Int)
