package com.gatekeeper.mpesa

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.payments.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object MpesaClient : PaymentProviderClient {
    override val provider = PaymentProvider.MPESA
    private val http = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private val baseUrl get() = if (AppConfig.mpesaEnvironment.equals("production", true)) "https://api.safaricom.co.ke" else "https://sandbox.safaricom.co.ke"
    fun isConfigured() = listOf(AppConfig.mpesaConsumerKey, AppConfig.mpesaConsumerSecret, AppConfig.mpesaShortCode, AppConfig.mpesaPasskey).all { it.isNotBlank() }

    private suspend fun token(): String {
        val credentials = Base64.getEncoder().encodeToString("${AppConfig.mpesaConsumerKey}:${AppConfig.mpesaConsumerSecret}".toByteArray())
        return http.get("$baseUrl/oauth/v1/generate?grant_type=client_credentials") { header(HttpHeaders.Authorization, "Basic $credentials") }.body<MpesaTokenResponse>().access_token
    }

    suspend fun initiate(project: ProjectRepository.ProjectRecord, phone: String): Result<String> = runCatching {
        require(isConfigured()) { "M-Pesa is not configured" }
        require(AppConfig.mpesaCallbackUrl.isNotBlank()) { "MPESA_CALLBACK_URL is not configured" }
        val amount = project.amountDue ?: error("No amount due configured for this project")
        val timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("${AppConfig.mpesaShortCode}${AppConfig.mpesaPasskey}$timestamp".toByteArray())
        val response = http.post("$baseUrl/mpesa/stkpush/v1/processrequest") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            setBody(MpesaStkRequest(AppConfig.mpesaShortCode, password, timestamp, Amount = amount.toLong(), PartyA = phone, PartyB = AppConfig.mpesaShortCode, PhoneNumber = phone, CallBackURL = AppConfig.mpesaCallbackUrl, AccountReference = project.slug, TransactionDesc = "Gatekeeper payment"))
        }.body<MpesaStkResponse>()
        val reference = response.CheckoutRequestID ?: error(response.ResponseDescription ?: "M-Pesa request failed")
        PaymentRepository.create(project.id, PaymentProvider.MPESA, reference, null, amount, "pending")
        reference
    }

    override suspend fun verify(reference: String): Result<VerifiedPayment> = runCatching {
        // Daraja's STK query is the authoritative polling/reconciliation endpoint.
        val timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("${AppConfig.mpesaShortCode}${AppConfig.mpesaPasskey}$timestamp".toByteArray())
        val response = http.post("$baseUrl/mpesa/stkpushquery/v1/query") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            setBody(mapOf("BusinessShortCode" to AppConfig.mpesaShortCode, "Password" to password, "Timestamp" to timestamp, "CheckoutRequestID" to reference))
        }.body<MpesaStkResponse>()
        val status = when (response.ResponseCode) { "0" -> VerifiedPaymentStatus.SUCCESS; "1032" -> VerifiedPaymentStatus.ABANDONED; else -> VerifiedPaymentStatus.PENDING }
        VerifiedPayment(provider, reference, status)
    }

    fun close() = http.close()
}
