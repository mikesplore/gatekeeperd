package com.gatekeeper.integrations

import com.gatekeeper.config.AppConfig
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable private data class ResendEmail(val from: String, val to: List<String>, val subject: String, val html: String)

object ResendClient {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.integrations.ResendClient")
    private val http = HttpClient { install(ContentNegotiation) { json(Json) } }

    suspend fun sendPasswordReset(email: String, link: String): Boolean {
        val key = AppConfig.resendApiKey.trim()
        val from = AppConfig.resendFromEmail.trim()
        if (key.isBlank() || from.isBlank()) return false
        return runCatching {
            http.post("https://api.resend.com/emails") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(ResendEmail(from, listOf(email), "Reset your Gatekeeperd password", "<p>We received a request to reset your Gatekeeperd password.</p><p><a href=\"$link\">Reset password</a></p><p>This link expires in 30 minutes. If you did not request this, you can ignore this email.</p>"))
            }.status.isSuccess()
        }.onFailure { logger.warn("Password reset email delivery failed: ${it.message}") }.getOrDefault(false)
    }
}
