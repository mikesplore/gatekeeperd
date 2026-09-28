package com.gatekeeper.integrations

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import com.gatekeeper.db.repositories.ProjectAdjustmentRepository
import com.gatekeeper.db.tables.AdjustmentType
import io.ktor.client.statement.bodyAsText

@Serializable data class ScribedSuspensionPayload(val project_id: String, val project_slug: String, val status: String, val reason: String, val occurred_at: String)
@Serializable data class ScribedPaymentPayload(val project_id: String, val project_slug: String, val provider: String, val provider_reference: String, val amount: String, val currency: String, val paid_at: String, val status: String = "success")
@Serializable data class ScribedLedgerAdjustment(val id: String, val type: String, val amount: String, val reason: String, val actor: String)
@Serializable data class ScribedLedgerPayload(val project_id: String, val project_slug: String, val ledger_version: String, val base_amount: String, val additional_charges: String, val discounts: String, val successful_payments: String, val outstanding_balance: String, val currency: String, val adjustments: List<ScribedLedgerAdjustment>)
@Serializable data class ScribedInvoiceEmailPayload(val invoice_id: Long)
@Serializable data class ScribedInvoiceCreatePayload(
    val client_name: String,
    val client_email: String?,
    val project_name: String,
    val description: String,
    val amount: String,
    val currency: String,
    val due_date: String?,
    val gatekeeper_project_id: String,
    val original_amount: String,
    val amount_paid: String
)

object ScribedIntegrationClient {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.integrations.ScribedIntegrationClient")
    private val http = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private val json = Json { encodeDefaults = true }

    data class InvoiceLookupResult(val status: HttpStatusCode?, val body: JsonObject? = null, val error: String? = null)

    data class InvoiceCreateResult(val status: HttpStatusCode?, val error: String? = null)

    suspend fun createInvoice(project: ProjectRepository.ProjectRecord, description: String, amount: String, balances: ProjectBalanceAdapter): InvoiceCreateResult {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) {
            return InvoiceCreateResult(null, "Scribed integration is not configured")
        }
        val payload = ScribedInvoiceCreatePayload(
            client_name = project.billingName?.takeIf { it.isNotBlank() }
                ?: project.customerName?.takeIf { it.isNotBlank() } ?: project.name,
            client_email = project.billingEmail ?: project.customerEmail,
            project_name = project.name,
            description = description,
            amount = amount,
            currency = project.currency,
            due_date = project.dueDate?.toString(),
            gatekeeper_project_id = project.id.toString(),
            original_amount = amount,
            amount_paid = balances.successfulPayments(project).toPlainString()
        )
        return runCatching {
            val response = http.post("$base/integrations/gatekeeper/invoices/ensure") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                header("X-Gatekeeper-Secret", secret)
                header("Idempotency-Key", "gatekeeper-invoice:${project.id}")
                setBody(payload)
            }
            if (response.status.isSuccess()) InvoiceCreateResult(response.status)
            else InvoiceCreateResult(response.status, response.bodyAsText().take(500))
        }.getOrElse { InvoiceCreateResult(null, it.message ?: it::class.simpleName) }
    }

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

    suspend fun serviceInvoiceStatus(serviceId: String): InvoiceLookupResult {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) {
            return InvoiceLookupResult(null, error = "Scribed integration is not configured")
        }
        return runCatching {
            val response = http.get("$base/integrations/gatekeeper/services/$serviceId/invoice") {
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

    suspend fun receiptPdfForPayment(
        project: ProjectRepository.ProjectRecord,
        payment: Payment,
        payments: PaymentDomainRepository,
        balances: ProjectBalanceAdapter
    ): Pair<HttpStatusCode, ByteArray?> {
        if (!ensureInvoice(project, balances = balances)) return HttpStatusCode.BadGateway to null
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return HttpStatusCode.ServiceUnavailable to null

        val adjustments = ProjectAdjustmentRepository.findByProjectId(project.id)
            .map { ScribedLedgerAdjustment(it.id.toString(), it.type.name, it.amount.toPlainString(), it.reason, it.actor) }
        val ledgerPayload = ScribedLedgerPayload(
            project.id.toString(), project.slug, "receipt-${payment.id}",
            balances.originalCharge(project).toPlainString(),
            balances.additionalCharges(project).toPlainString(),
            balances.discounts(project).toPlainString(),
            balances.successfulPayments(project).toPlainString(),
            balances.outstandingBalance(project).toPlainString(), project.currency, adjustments
        )
        val ledgerResponse = runCatching {
            http.post("$base/integrations/gatekeeper/ledger") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiToken")
                header("X-Gatekeeper-Secret", secret)
                header("Idempotency-Key", "ledger:${project.id}:receipt-${payment.id}")
                setBody(ledgerPayload)
            }
        }.getOrElse { return HttpStatusCode.BadGateway to null }
        if (!ledgerResponse.status.isSuccess()) return ledgerResponse.status to null

        val successfulPayments = payments.findByProjectId(project.id)
            .filter { it.status == "success" }
            .sortedBy { it.paidAt ?: it.createdAt ?: java.time.LocalDateTime.MIN }
        for (successfulPayment in successfulPayments) {
            val payload = ScribedPaymentPayload(
                project.id.toString(), project.slug, successfulPayment.provider,
                successfulPayment.providerReference, successfulPayment.amount.toPlainString(), project.currency,
                (successfulPayment.paidAt ?: successfulPayment.createdAt ?: java.time.LocalDateTime.MIN).toString()
            )
            val response = runCatching {
                http.post("$base/integrations/gatekeeper/payments") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $apiToken")
                    header("X-Gatekeeper-Secret", secret)
                    header("Idempotency-Key", "payment:${project.id}:${successfulPayment.provider}:${successfulPayment.providerReference}")
                    setBody(payload)
                }
            }.getOrElse { return HttpStatusCode.BadGateway to null }
            if (!response.status.isSuccess()) return response.status to null
        }

        val invoiceLookup = invoiceStatus(project.id.toString())
        val receiptId = invoiceLookup.body?.get("payments")?.jsonArray?.firstNotNullOfOrNull { item ->
            val record = item.jsonObject
            if (record["provider_reference"]?.jsonPrimitive?.content == payment.providerReference) {
                record["id"]?.jsonPrimitive?.longOrNull
            } else null
        } ?: return HttpStatusCode.NotFound to null
        return receiptPdf(receiptId)
    }

    fun notifyInvoiceDue(project: ProjectRepository.ProjectRecord) {
        runBlocking {
            val lookup = invoiceStatus(project.id.toString())
            val invoiceId = lookup.body?.get("invoice")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return@runBlocking
            IntegrationOutboxRepository.enqueue("invoice_email", "invoice-email:$invoiceId", json.encodeToString(ScribedInvoiceEmailPayload(invoiceId)))
        }
    }

    suspend fun receiptPdf(paymentId: Long): Pair<HttpStatusCode, ByteArray?> {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return HttpStatusCode.ServiceUnavailable to null
        return runCatching {
            val response = http.get("$base/payments/$paymentId/receipt") {
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

    fun notifyLedger(project: ProjectRepository.ProjectRecord, ledgerVersion: String, balances: ProjectBalanceAdapter) {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/')
        if (base.isBlank() || AppConfig.scribedIntegrationSecret.trim().isBlank()) return
        val adjustments = ProjectAdjustmentRepository.findByProjectId(project.id).map { ScribedLedgerAdjustment(it.id.toString(), it.type.name, it.amount.toPlainString(), it.reason, it.actor) }
        val payload = ScribedLedgerPayload(project.id.toString(), project.slug, ledgerVersion,
            balances.originalCharge(project).toPlainString(),
            balances.additionalCharges(project).toPlainString(),
            balances.discounts(project).toPlainString(),
            balances.successfulPayments(project).toPlainString(),
            balances.outstandingBalance(project).toPlainString(), project.currency, adjustments)
        IntegrationOutboxRepository.enqueue("ledger", "ledger:${project.id}:$ledgerVersion", json.encodeToString(payload))
    }

    fun syncProject(project: ProjectRepository.ProjectRecord, payments: PaymentDomainRepository, balances: ProjectBalanceAdapter): Int {
        notifyLedger(project, "sync-${java.util.UUID.randomUUID()}", balances)
        val successfulPayments = payments.findByProjectId(project.id)
            .filter { it.status == "success" }
            .sortedBy { it.paidAt ?: it.createdAt ?: java.time.LocalDateTime.MIN }
        successfulPayments.forEach { payment ->
            notifyPayment(
                project = project,
                provider = payment.provider,
                reference = payment.providerReference,
                amount = payment.amount.toPlainString(),
                currency = project.currency,
                paidAt = (payment.paidAt ?: payment.createdAt ?: java.time.LocalDateTime.MIN).toString()
            )
        }
        return successfulPayments.size
    }

    suspend fun ensureInvoice(project: ProjectRepository.ProjectRecord, balances: ProjectBalanceAdapter, description: String = "Services for ${project.name}"): Boolean {
        val lookup = invoiceStatus(project.id.toString())
        if (lookup.body != null) return true
        if (lookup.status != HttpStatusCode.NotFound) {
            logger.warn("Scribed invoice check failed for project=${project.id}: status=${lookup.status}, error=${lookup.error}")
            return false
        }
        val amount = balances.originalCharge(project) + balances.additionalCharges(project) - balances.discounts(project)
        if (amount <= java.math.BigDecimal.ZERO) return false
        val created = createInvoice(project, description, amount.toPlainString(), balances)
        if (created.status == null || !created.status.isSuccess()) {
            logger.warn("Could not ensure Scribed invoice for project=${project.id}: status=${created.status}, error=${created.error}")
            return false
        }
        val confirmed = invoiceStatus(project.id.toString())
        if (confirmed.body == null) {
            logger.warn("Scribed invoice ensure did not create a retrievable invoice for project=${project.id}: status=${confirmed.status}, error=${confirmed.error}")
        }
        return confirmed.body != null
    }

    suspend fun deliver(event: IntegrationOutboxRepository.Event, payments: PaymentDomainRepository, balances: ProjectBalanceAdapter): Boolean {
        val base = AppConfig.scribedCallbackUrl.trim().trimEnd('/'); val secret = AppConfig.scribedIntegrationSecret.trim()
        val apiToken = AppConfig.scribedApiToken.trim()
        if (base.isBlank() || secret.isBlank() || apiToken.isBlank()) return false
        if (event.eventType == "payment" || event.eventType == "ledger") {
            val projectId = runCatching {
                when (event.eventType) {
                    "payment" -> json.decodeFromString<ScribedPaymentPayload>(event.payload).project_id
                    else -> json.decodeFromString<ScribedLedgerPayload>(event.payload).project_id
                }
            }.getOrNull() ?: return false
            val projectUuid = runCatching { java.util.UUID.fromString(projectId) }.getOrNull() ?: return false
            val project = ProjectRepository.findById(projectUuid) ?: return false
            val beforeEnsure = invoiceStatus(projectId)
            if (beforeEnsure.status == HttpStatusCode.NotFound) {
                if (!ensureInvoice(project, balances)) return false
                if (event.eventType == "payment") syncProject(project, payments, balances)
            } else if (beforeEnsure.body == null) {
                logger.warn("Scribed invoice check failed before ${event.eventType} delivery project=${project.id}: status=${beforeEnsure.status}, error=${beforeEnsure.error}")
                return false
            }
        }
        if (event.eventType == "invoice_email") {
            return runCatching {
                val payload = json.decodeFromString<ScribedInvoiceEmailPayload>(event.payload)
                val response = http.post("$base/invoices/${payload.invoice_id}/send") {
                    header(HttpHeaders.Authorization, "Bearer $apiToken")
                    header("X-Gatekeeper-Secret", secret)
                    header("Idempotency-Key", event.idempotencyKey)
                }
                response.status.isSuccess()
            }.getOrElse { logger.warn("Scribed invoice email failed id=${event.id}: ${it.message}"); false }
        }
        val path = when (event.eventType) { "payment" -> "/integrations/gatekeeper/payments"; "ledger" -> "/integrations/gatekeeper/ledger"; else -> "/integrations/gatekeeper/suspensions" }
        return runCatching {
            val response = http.post("$base$path") { contentType(ContentType.Application.Json); header(HttpHeaders.Authorization, "Bearer $apiToken"); header("X-Gatekeeper-Secret", secret); header("Idempotency-Key", event.idempotencyKey); setBody(event.payload) }
            if (!response.status.isSuccess()) logger.warn("Scribed outbox delivery rejected id=${event.id} type=${event.eventType} status=${response.status} body=${response.bodyAsText().take(500)}")
            response.status.isSuccess()
        }.getOrElse { logger.warn("Scribed outbox delivery failed id=${event.id}: ${it.message}"); false }
    }
}
