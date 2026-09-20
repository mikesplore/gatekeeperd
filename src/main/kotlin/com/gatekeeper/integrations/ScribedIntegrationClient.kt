package com.gatekeeper.integrations

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import com.gatekeeper.db.repositories.IntegrationOutboxRepository

@Serializable data class ScribedSuspensionPayload(val project_id: String, val project_slug: String, val status: String, val reason: String, val occurred_at: String)
@Serializable data class ScribedPaymentPayload(val project_id: String, val project_slug: String, val provider: String, val provider_reference: String, val amount: String, val currency: String, val paid_at: String, val status: String = "success")

object ScribedIntegrationClient {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.integrations.ScribedIntegrationClient")
    private val http = HttpClient()
    private val json = Json { encodeDefaults = true }

    suspend fun invoiceStatus(projectId: String): JsonObject? {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return null
        return runCatching {
            http.get("$base/integrations/gatekeeper/invoices/$projectId") {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                header("X-Gatekeeper-Secret", secret)
            }.body<JsonObject>()
        }.getOrNull()
    }
    fun notifySuspension(project: ProjectRepository.ProjectRecord, reason: String) {
        runBlocking {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        if (base.isBlank() || secret.isBlank()) return@runBlocking
        IntegrationOutboxRepository.enqueue("suspension", "suspension:${project.id}:$reason", json.encodeToString(ScribedSuspensionPayload(project.id.toString(), project.slug, project.status, reason, java.time.OffsetDateTime.now().toString())))
        }
    }

    fun notifyPayment(project: ProjectRepository.ProjectRecord, provider: String, reference: String, amount: String, currency: String, paidAt: String) {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/'); val secret = AppConfig.scribedIntegrationSecret.trim()
        if (base.isBlank() || secret.isBlank()) return
        IntegrationOutboxRepository.enqueue("payment", "payment:${project.id}:$provider:$reference", json.encodeToString(ScribedPaymentPayload(project.id.toString(), project.slug, provider, reference, amount, currency, paidAt)))
    }

    suspend fun deliver(event: IntegrationOutboxRepository.Event): Boolean {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/'); val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return false
        val path = if (event.eventType == "payment") "/integrations/gatekeeper/payments" else "/integrations/gatekeeper/suspensions"
        return runCatching { http.post("$base$path") { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $apiToken"); header("X-Gatekeeper-Secret", secret); header("Idempotency-Key", event.idempotencyKey); setBody(event.payload) }.status.isSuccess() }.getOrElse { logger.warn("Scribed outbox delivery failed id=${event.id}: ${it.message}"); false }
    }
}
