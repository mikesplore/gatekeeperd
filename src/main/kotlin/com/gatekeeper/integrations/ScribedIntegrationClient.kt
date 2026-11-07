package com.gatekeeper.integrations

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.security.SecretValueCipher
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.time.LocalDate

/** The Scribed boundary is intentionally limited to document rendering, retrieval, and health checks. */
class ScribedIntegrationClient(
    private val settings: AppSettings.IntegrationSettings,
    private val credentials: ProviderCredentialStore
) : java.io.Closeable {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val http = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 20_000
        }
    }

    @Serializable data class DocumentApiSettings(
        val baseUrl: String,
        val apiToken: String,
        val clientId: Int? = null,
        val clientName: String? = null,
        val clientAddress: String? = null,
        val contactEmail: String? = null,
        val phone: String? = null
    )
    @Serializable data class ScribedClientProfile(
        val name: String,
        val address: String? = null,
        val contact_email: String? = null,
        val phone: String? = null
    )
    @Serializable private data class ScribedClientRegistrationResponse(val id: Int, val api_token: String? = null)
    @Serializable data class ScribedClientProfileResponse(
        val id: Int,
        val name: String,
        val address: String? = null,
        val contact_email: String? = null,
        val phone: String? = null
    )
    @Serializable data class DocumentSettingsStatus(
        val baseUrl: String,
        val configured: Boolean,
        val tokenConfigured: Boolean,
        val clientRegistered: Boolean,
        val clientName: String?,
        val clientAddress: String?,
        val contactEmail: String?,
        val phone: String?,
        val credentialVersion: Int?,
        val environmentTokenAvailable: Boolean
    )
    @Serializable data class ReceiptRequest(
        val client_name: String,
        val client_email: String?,
        val project_name: String,
        val description: String,
        val amount: String,
        val currency: String,
        val received_date: String,
        val payment_method: String? = null,
        val payment_reference: String? = null
    )
    @Serializable data class InvoiceRequest(
        val client_name: String,
        val client_email: String?,
        val project_name: String,
        val description: String,
        val amount: String,
        val currency: String,
        val issue_date: String,
        val due_date: String?,
        val invoice_number: String,
        val payment_instructions: String = "Payment details will be shared by email.",
        val paid_amount: String = "0.00",
        val balance_due: String? = null,
        val items: List<InvoiceItem>? = null,
        val payment_status: String? = null
    )
    @Serializable data class InvoiceItem(val name: String, val description: String? = null, val amount: String)

    @Serializable data class HealthResult(val configured: Boolean, val reachable: Boolean, val status: Int? = null, val message: String)

    override fun close() = http.close()

    private fun documentSettings(): DocumentApiSettings {
        val stored = credentials.findCurrent("scribed", "api_client", "primary")?.payload
            ?.let { runCatching { json.decodeFromString<DocumentApiSettings>(it) }.getOrNull() }
        return stored ?: DocumentApiSettings(
            settings.scribedBaseUrl.trim().trimEnd('/'), settings.scribedApiClientToken.trim()
        )
    }

    fun documentSettingsStatus(): DocumentSettingsStatus {
        val config = documentSettings()
        val metadata = credentials.findCurrent("scribed", "api_client", "primary")?.metadata
        return DocumentSettingsStatus(
            config.baseUrl,
            config.baseUrl.isNotBlank() && config.apiToken.isNotBlank(),
            config.apiToken.isNotBlank(),
            config.clientId != null,
            config.clientName,
            config.clientAddress,
            config.contactEmail,
            config.phone,
            metadata?.version,
            settings.scribedApiClientToken.isNotBlank() || settings.scribedApiToken.isNotBlank()
        )
    }

    suspend fun importEnvironmentClientToken() {
        val baseUrl = settings.scribedBaseUrl.trim().trimEnd('/')
        val environmentToken = settings.scribedApiClientToken.trim()
        val adminToken = settings.scribedApiToken.trim().ifBlank { environmentToken }
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in setOf("", "/")) {
            "SCRIBED_BASE_URL must be a service root URL"
        }
        require((environmentToken.isNotBlank() || adminToken.isNotBlank()) &&
            environmentToken.length <= 4096 && adminToken.length <= 4096) {
            "Set Scribed's API token in Gatekeeperd's environment"
        }
        check(SecretValueCipher.isConfigured()) { "Secret encryption is not configured" }

        if (environmentToken.isNotBlank()) {
            val config = DocumentApiSettings(baseUrl, environmentToken, clientName = "Gatekeeperd")
            val profile = runCatching { fetchClientProfile(config) }.getOrNull()
            if (profile != null) {
                credentials.createOrRotate(
                    "scribed", "api_client", "Scribed API client", "primary",
                    json.encodeToString(config.withProfile(profile))
                )
                return
            }
        }

        // If the configured token is Scribed's administrator token, register a scoped
        // Gatekeeperd client automatically and persist only the scoped token it returns.
        val previous = documentSettings()
        registerDocumentClient(
            baseUrl,
            adminToken,
            ScribedClientProfile(
                previous.clientName?.takeIf(String::isNotBlank) ?: "Gatekeeperd",
                previous.clientAddress, previous.contactEmail, previous.phone
            )
        )
    }

    suspend fun updateDocumentClientProfile(profile: ScribedClientProfile) {
        val normalized = profile.normalized()
        require(normalized.name.isNotBlank() && normalized.name.length <= 120) { "Business name must contain 1 to 120 characters" }
        require((normalized.address?.length ?: 0) <= 1000 && (normalized.contact_email?.length ?: 0) <= 254 && (normalized.phone?.length ?: 0) <= 40) {
            "Business address, contact email, or phone is too long"
        }
        require(normalized.contact_email == null || normalized.contact_email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            "Business contact email is invalid"
        }
        check(SecretValueCipher.isConfigured()) { "Secret encryption is not configured" }
        val config = documentSettings()
        check(config.apiToken.isNotBlank() && config.baseUrl.isNotBlank()) { "Scribed client token is not configured" }
        val response = http.patch("${config.baseUrl}/api-clients/me") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
            setBody(normalized)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Scribed profile update returned HTTP ${response.status.value}")
        }
        val updated = runCatching { json.decodeFromString<ScribedClientProfileResponse>(response.bodyAsText()) }
            .getOrElse { throw IllegalStateException("Scribed returned an invalid API client profile") }
        credentials.createOrRotate(
            "scribed", "api_client", "Scribed API client", "primary",
            json.encodeToString(config.withProfile(updated))
        )
    }

    suspend fun documentClientProfile(): ScribedClientProfileResponse = fetchClientProfile(documentSettings())

    private suspend fun fetchClientProfile(config: DocumentApiSettings): ScribedClientProfileResponse {
        check(config.apiToken.isNotBlank() && config.baseUrl.isNotBlank()) { "Scribed client token is not configured" }
        val response = http.get("${config.baseUrl}/api-clients/me") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
        }
        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.Forbidden) {
                throw IllegalArgumentException("SCRIBED_API_CLIENT_TOKEN must be a scoped API client token, not Scribed's administrator token")
            }
            throw IllegalStateException("Scribed did not return the scoped API client profile (HTTP ${response.status.value})")
        }
        return runCatching { json.decodeFromString<ScribedClientProfileResponse>(response.bodyAsText()) }
            .getOrElse { throw IllegalStateException("Scribed returned an invalid scoped API client profile") }
    }

    private fun DocumentApiSettings.withProfile(profile: ScribedClientProfileResponse) = copy(
        clientId = profile.id,
        clientName = profile.name,
        clientAddress = profile.address,
        contactEmail = profile.contact_email,
        phone = profile.phone
    )

    private fun DocumentApiSettings.withProfile(profile: ScribedClientProfile) = copy(
        clientName = profile.name,
        clientAddress = profile.address,
        contactEmail = profile.contact_email,
        phone = profile.phone
    )

    private fun ScribedClientProfile.normalized() = copy(
        name = name.trim(), address = address?.trim()?.takeIf(String::isNotEmpty),
        contact_email = contact_email?.trim()?.takeIf(String::isNotEmpty),
        phone = phone?.trim()?.takeIf(String::isNotEmpty)
    )

    suspend fun registerDocumentClient(baseUrl: String, adminToken: String, profile: ScribedClientProfile) {
        val normalized = baseUrl.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Scribed URL must be an http(s) URL without credentials, query, or fragment"
        }
        require(adminToken.isNotBlank() && adminToken.length <= 4096) { "Scribed administrator token is required to register the Gatekeeperd client" }
        require(profile.name.isNotBlank() && profile.name.length <= 120) { "Client name must contain 1 to 120 characters" }
        require((profile.address?.length ?: 0) <= 1000 && (profile.contact_email?.length ?: 0) <= 254 && (profile.phone?.length ?: 0) <= 40) {
            "Client address, contact email, or phone is too long"
        }
        check(SecretValueCipher.isConfigured()) { "Secret encryption is not configured" }
        val previous = documentSettings()
        val updating = previous.clientId != null && previous.baseUrl == normalized && previous.apiToken.isNotBlank()
        val response = http.request {
            method = if (updating) HttpMethod.Put else HttpMethod.Post
            url(if (updating) "$normalized/api-clients/${previous.clientId}" else "$normalized/api-clients")
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${adminToken.trim()}")
            setBody(profile)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Scribed client registration returned HTTP ${response.status.value}")
        }
        val result = runCatching { json.decodeFromString<ScribedClientRegistrationResponse>(response.bodyAsText()) }
            .getOrElse { throw IllegalStateException("Scribed returned an invalid client registration response") }
        val clientToken = if (updating) previous.apiToken else result.api_token
            ?: throw IllegalStateException("Scribed did not return a client token")
        credentials.createOrRotate(
            "scribed", "api_client", "Scribed API client", "primary",
            json.encodeToString(DocumentApiSettings(
                normalized, clientToken, result.id, profile.name.trim(), profile.address?.trim()?.takeIf(String::isNotEmpty),
                profile.contact_email?.trim()?.takeIf(String::isNotEmpty), profile.phone?.trim()?.takeIf(String::isNotEmpty)
            ))
        )
    }

    suspend fun healthCheck(): HealthResult = healthCheck(documentSettings())

    private suspend fun healthCheck(config: DocumentApiSettings): HealthResult {
        if (config.baseUrl.isBlank() || config.apiToken.isBlank()) {
            return HealthResult(false, false, message = "Scribed URL or API client token is not configured")
        }
        return runCatching {
            val health = http.get("${config.baseUrl}/health")
            if (!health.status.isSuccess()) {
                HealthResult(true, false, health.status.value, "Scribed health check returned HTTP ${health.status.value}")
            } else {
                // This endpoint accepts only scoped client tokens, not the administrator token.
                val tokenProbe = http.get("${config.baseUrl}/api-clients/me") {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
                }
                val authorized = tokenProbe.status.isSuccess()
                HealthResult(
                    true, authorized, tokenProbe.status.value,
                    if (authorized) "Scribed is healthy and the scoped API client token is accepted"
                    else "Scribed is healthy but rejected scoped API client access (HTTP ${tokenProbe.status.value}); verify the token is not the administrator token"
                )
            }
        }.getOrElse { HealthResult(true, false, message = "Scribed health check could not connect") }
    }

    suspend fun generateInvoice(
        project: ProjectRecord, projectName: String, description: String, amount: String,
        currency: String, dueDate: LocalDate?, invoiceKey: String, issueDate: LocalDate,
        paidAmount: String, balanceDue: String, items: List<InvoiceItem>, paymentStatus: String
    ): Pair<HttpStatusCode, ByteArray?> {
        val config = documentSettings()
        if (config.baseUrl.isBlank() || config.apiToken.isBlank()) return HttpStatusCode.ServiceUnavailable to null
        val payload = InvoiceRequest(
            client_name = billingName(project),
            client_email = project.billingEmail ?: project.customerEmail,
            project_name = projectName,
            description = description,
            amount = amount,
            currency = currency,
            issue_date = issueDate.toString(),
            due_date = dueDate?.toString(),
            invoice_number = "GK-$invoiceKey",
            paid_amount = paidAmount,
            balance_due = balanceDue,
            items = items.takeIf { it.isNotEmpty() },
            payment_status = paymentStatus
        )
        return postPdf("/generate/invoice", payload, config)
    }

    suspend fun issueReceipt(project: ProjectRecord, payment: Payment, description: String): Pair<HttpStatusCode, ByteArray?> {
        val config = documentSettings()
        if (config.baseUrl.isBlank() || config.apiToken.isBlank()) return HttpStatusCode.ServiceUnavailable to null
        val payload = ReceiptRequest(
            client_name = billingName(project),
            client_email = project.billingEmail ?: project.customerEmail,
            project_name = project.name,
            description = description,
            amount = payment.amount.toPlainString(),
            currency = payment.currency?.takeIf(String::isNotBlank) ?: project.currency,
            received_date = (payment.paidAt?.toLocalDate() ?: payment.createdAt?.toLocalDate() ?: LocalDate.now()).toString(),
            payment_method = payment.provider,
            payment_reference = payment.providerReference
        )
        return postPdf("/receipts", payload, config, "gatekeeper-receipt:${payment.id}")
    }

    suspend fun receiptPdfForPayment(
        project: ProjectRecord, payment: Payment, @Suppress("UNUSED_PARAMETER") payments: PaymentDomainRepository,
        @Suppress("UNUSED_PARAMETER") balances: ProjectBalanceAdapter
    ): Pair<HttpStatusCode, ByteArray?> = issueReceipt(project, payment, "Payment received for ${project.name}")

    suspend fun receiptPdfForServicePayment(
        project: ProjectRecord, serviceName: String, payment: Payment,
        @Suppress("UNUSED_PARAMETER") payments: PaymentDomainRepository,
        @Suppress("UNUSED_PARAMETER") balances: ProjectBalanceAdapter
    ): Pair<HttpStatusCode, ByteArray?> = issueReceipt(
        project.copy(name = "${project.name} — $serviceName"), payment,
        "Payment received for ${project.name} — $serviceName"
    )

    private suspend inline fun <reified T : Any> postPdf(
        path: String, payload: T, config: DocumentApiSettings, idempotencyKey: String? = null
    ): Pair<HttpStatusCode, ByteArray?> = runCatching {
        val response = http.post("${config.baseUrl}$path") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
            if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey.take(128))
            setBody(payload)
        }
        response.status to response.body<ByteArray>().takeIf { response.status.isSuccess() }
    }.getOrElse { HttpStatusCode.BadGateway to null }

    private fun billingName(project: ProjectRecord): String =
        project.billingName?.takeIf(String::isNotBlank) ?: project.customerName?.takeIf(String::isNotBlank) ?: project.name
}
