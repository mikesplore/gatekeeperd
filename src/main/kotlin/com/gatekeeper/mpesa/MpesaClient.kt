package com.gatekeeper.mpesa

import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.InitiatedPayment
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object MpesaClient : PaymentGateway {
    override val provider = "mpesa"
    private val logger = LoggerFactory.getLogger("com.gatekeeper.mpesa.MpesaClient")
    private val http = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private val baseUrl get() = if (AppConfig.mpesaEnvironment.equals("production", true)) "https://api.safaricom.co.ke" else "https://sandbox.safaricom.co.ke"
    fun isConfigured() = listOf(
        AppConfig.mpesaConsumerKey,
        AppConfig.mpesaConsumerSecret,
        AppConfig.mpesaShortCode,
        AppConfig.mpesaPasskey,
        AppConfig.mpesaCallbackUrl
    ).all { it.isNotBlank() }

    override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> = runCatching {
        require(isConfigured()) { "M-Pesa is not configured" }
        require(command.currency.equals("KES", ignoreCase = true)) { "M-Pesa payments are only supported in KES" }
        val phone = command.phone ?: error("A phone number is required for M-Pesa payments")
        val timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("${AppConfig.mpesaShortCode}${AppConfig.mpesaPasskey}$timestamp".toByteArray())
        val response = http.post("$baseUrl/mpesa/stkpush/v1/processrequest") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            setBody(MpesaStkRequest(AppConfig.mpesaShortCode, password, timestamp, TransactionType = "CustomerPayBillOnline", Amount = command.amount.longValueExact(), PartyA = phone, PartyB = AppConfig.mpesaShortCode, PhoneNumber = phone, CallBackURL = AppConfig.mpesaCallbackUrl, AccountReference = command.projectSlug, TransactionDesc = "Gatekeeper payment"))
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) error("Daraja STK Push returned HTTP ${response.status.value}: ${responseBody.take(500)}")
        val responseData = Json.decodeFromString<MpesaStkResponse>(responseBody)
        if (responseData.ResponseCode != null && responseData.ResponseCode != "0") error("Daraja STK Push rejected the request (code=${responseData.ResponseCode}): ${responseData.ResponseDescription ?: responseData.CustomerMessage ?: "no description"}")
        InitiatedPayment(responseData.CheckoutRequestID ?: error(responseData.ResponseDescription ?: responseData.CustomerMessage ?: "Daraja returned no CheckoutRequestID"))
    }.onFailure { error ->
        val callbackHost = runCatching { java.net.URI(AppConfig.mpesaCallbackUrl).host }.getOrNull() ?: "invalid"
        logger.error("M-Pesa STK initiation failed for project={} environment={} callbackHost={}: {}", command.projectSlug, AppConfig.mpesaEnvironment, callbackHost, error.message)
    }

    private suspend fun token(): String {
        val credentials = Base64.getEncoder().encodeToString("${AppConfig.mpesaConsumerKey}:${AppConfig.mpesaConsumerSecret}".toByteArray())
        val response = http.get("$baseUrl/oauth/v1/generate?grant_type=client_credentials") {
            header(HttpHeaders.Authorization, "Basic $credentials")
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Daraja OAuth returned HTTP ${response.status.value}: ${responseBody.take(500)}")
        }
        return Json.decodeFromString<MpesaTokenResponse>(responseBody).access_token
    }

    override suspend fun verify(reference: String): Result<VerifiedPayment> = runCatching {
        // Daraja's STK query is the authoritative polling/reconciliation endpoint.
        val timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("${AppConfig.mpesaShortCode}${AppConfig.mpesaPasskey}$timestamp".toByteArray())
        val response = http.post("$baseUrl/mpesa/stkpushquery/v1/query") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            setBody(mapOf("BusinessShortCode" to AppConfig.mpesaShortCode, "Password" to password, "Timestamp" to timestamp, "CheckoutRequestID" to reference))
        }.body<MpesaStkResponse>()
        val status = when (response.ResponseCode) { "0" -> "success"; "1032" -> "abandoned"; else -> "pending" }
        VerifiedPayment(status)
    }

    fun close() = http.close()
}
