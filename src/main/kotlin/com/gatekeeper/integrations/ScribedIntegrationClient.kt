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
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.payments.ProjectBalanceService
import io.ktor.client.statement.bodyAsText

@Serializable data class ScribedSuspensionPayload(val project_id: String, val project_slug: String, val status: String, val reason: String, val occurred_at: String)
@Serializable data class ScribedPaymentPayload(val project_id: String, val project_slug: String, val provider: String, val provider_reference: String, val amount: String, val currency: String, val paid_at: String, val status: String = "success")
@Serializable data class ScribedLedgerAdjustment(val id: String, val type: String, val amount: String, val reason: String, val actor: String)
@Serializable data class ScribedLedgerPayload(val project_id: String, val project_slug: String, val ledger_version: String, val base_amount: String, val additional_charges: String, val discounts: String, val successful_payments: String, val outstanding_balance: String, val currency: String, val adjustments: List<ScribedLedgerAdjustment>)

object ScribedIntegrationClient {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.integrations.ScribedIntegrationClient")
    private val http = HttpClient()
    private val json = Json { encodeDefaults = true }

    data class InvoiceLookupResult(val status: HttpStatusCode?, val body: JsonObject? = null, val error: String? = null)

    suspend fun invoiceStatus(projectId: String): InvoiceLookupResult {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) {
            return InvoiceLookupResult(null, error = "Scribed integration is not configured")
        }
        return runCatching {
            val response = http.get("$base/integrations/gatekeeper/invoices/$projectId") {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                header("X-Gatekeeper-Secret", secret)
            }
            val responseBody = response.bodyAsText()
            if (!response.status.isSuccess()) {
                InvoiceLookupResult(response.status, error = responseBody.take(500))
            } else {
                InvoiceLookupResult(response.status, Json.decodeFromString<JsonObject>(responseBody))
            }
        }.getOrElse { InvoiceLookupResult(null, error = it.message ?: it::class.simpleName) }
    }

    suspend fun invoicePdf(invoiceId: Long): Pair<HttpStatusCode, ByteArray?> {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return HttpStatusCode.ServiceUnavailable to null
        return runCatching {
            val response = http.get("$base/invoices/$invoiceId") {
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                header("X-Gatekeeper-Secret", secret)
            }
            response.status to response.body<ByteArray>().takeIf { response.status.isSuccess() }
        }.getOrElse { HttpStatusCode.BadGateway to null }
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

    fun notifyLedger(project: ProjectRepository.ProjectRecord, ledgerVersion: String) {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        if (base.isBlank() || AppConfig.scribedIntegrationSecret.trim().isBlank()) return
        val adjustments = ProjectAdjustmentRepository.findByProjectId(project.id).map { ScribedLedgerAdjustment(it.id.toString(), it.type.name, it.amount.toPlainString(), it.reason, it.actor) }
        val payload = ScribedLedgerPayload(project.id.toString(), project.slug, ledgerVersion,
            ProjectBalanceService.originalCharge(project).toPlainString(),
            ProjectBalanceService.additionalCharges(project).toPlainString(),
            ProjectBalanceService.discounts(project).toPlainString(),
            ProjectBalanceService.successfulPayments(project).toPlainString(),
            ProjectBalanceService.outstandingBalance(project).toPlainString(), project.currency, adjustments)
        IntegrationOutboxRepository.enqueue("ledger", "ledger:${project.id}:$ledgerVersion", json.encodeToString(payload))
    }

    suspend fun deliver(event: IntegrationOutboxRepository.Event): Boolean {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/'); val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return false
        val path = when (event.eventType) { "payment" -> "/integrations/gatekeeper/payments"; "ledger" -> "/integrations/gatekeeper/ledger"; else -> "/integrations/gatekeeper/suspensions" }
        return runCatching { http.post("$base$path") { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $apiToken"); header("X-Gatekeeper-Secret", secret); header("Idempotency-Key", event.idempotencyKey); setBody(event.payload) }.status.isSuccess() }.getOrElse { logger.warn("Scribed outbox delivery failed id=${event.id}: ${it.message}"); false }
    }
}
