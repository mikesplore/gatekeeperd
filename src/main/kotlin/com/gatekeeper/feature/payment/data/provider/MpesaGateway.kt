package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.InitiatedPayment
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import com.gatekeeper.plugins.DistributedLock
import io.ktor.client.*
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
import kotlinx.coroutines.delay

class MpesaGateway(private val settings: AppSettings.PaymentSettings) : PaymentGateway {
    override val provider = "mpesa"
    private val logger = LoggerFactory.getLogger("com.gatekeeper.feature.payment.MpesaGateway")
    private val http = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private val baseUrl get() = if (settings.mpesaEnvironment.equals("production", true)) "https://api.safaricom.co.ke" else "https://sandbox.safaricom.co.ke"
    fun isConfigured() = listOf(settings.mpesaConsumerKey, settings.mpesaConsumerSecret, settings.mpesaShortCode, settings.mpesaPasskey, settings.mpesaCallbackUrl).all { it.isNotBlank() } && settings.mpesaCallbackToken.length >= 32

    override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> = runCatching {
        require(isConfigured()) { "M-Pesa is not configured" }
        require(command.currency.equals("KES", ignoreCase = true)) { "M-Pesa payments are only supported in KES" }
        val phone = command.phone ?: error("A phone number is required for M-Pesa payments")
        val timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("${settings.mpesaShortCode}${settings.mpesaPasskey}$timestamp".toByteArray())
        val response = http.post("$baseUrl/mpesa/stkpush/v1/processrequest") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            val callbackUrl = "${settings.mpesaCallbackUrl.trimEnd('/')}/${settings.mpesaCallbackToken}"
            setBody(MpesaStkRequest(settings.mpesaShortCode, password, timestamp, TransactionType = "CustomerPayBillOnline", Amount = command.amount.longValueExact(), PartyA = phone, PartyB = settings.mpesaShortCode, PhoneNumber = phone, CallBackURL = callbackUrl, AccountReference = command.projectSlug, TransactionDesc = "Gatekeeper payment"))
        }
        val responseBody = response.bodyAsText()
        if (!response.status.isSuccess()) error("Daraja STK Push returned HTTP ${response.status.value}: ${responseBody.take(500)}")
        val responseData = Json.decodeFromString<MpesaStkResponse>(responseBody)
        if (responseData.ResponseCode != null && responseData.ResponseCode != "0") error("Daraja STK Push rejected the request (code=${responseData.ResponseCode}): ${responseData.ResponseDescription ?: responseData.CustomerMessage ?: "no description"}")
        InitiatedPayment(responseData.CheckoutRequestID ?: error(responseData.ResponseDescription ?: responseData.CustomerMessage ?: "Daraja returned no CheckoutRequestID"))
    }.onFailure { error ->
        val callbackHost = runCatching { java.net.URI(settings.mpesaCallbackUrl).host }.getOrNull() ?: "invalid"
        logger.error("M-Pesa STK initiation failed for project={} environment={} callbackHost={}: {}", command.projectSlug, settings.mpesaEnvironment, callbackHost, error.message)
    }

    private suspend fun token(): String {
        val credentials = Base64.getEncoder().encodeToString("${settings.mpesaConsumerKey}:${settings.mpesaConsumerSecret}".toByteArray())
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
        // This query is reserved for explicit checks; callbacks settle normal payments.
        val timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val password = Base64.getEncoder().encodeToString("${settings.mpesaShortCode}${settings.mpesaPasskey}$timestamp".toByteArray())
        val (responseStatus, responseBody) = DistributedLock.withSuspendLock("mpesa-stk-status-query") {
            // Daraja enforces a low burst limit on status queries. Serialize
            // app instances and leave a conservative gap between manual checks.
            delay(3_000)
            val response = http.post("$baseUrl/mpesa/stkpushquery/v1/query") {
                bearerAuth(token()); contentType(ContentType.Application.Json)
                setBody(mapOf("BusinessShortCode" to settings.mpesaShortCode, "Password" to password, "Timestamp" to timestamp, "CheckoutRequestID" to reference))
            }
            response.status to response.bodyAsText()
        }
        if (!responseStatus.isSuccess()) {
            error("Daraja STK query returned HTTP ${responseStatus.value}: ${responseBody.take(500)}")
        }
        val query = Json.decodeFromString<MpesaStkResponse>(responseBody)
        if (query.ResponseCode != null && query.ResponseCode != "0") {
            error("Daraja STK query rejected (code=${query.ResponseCode}): ${query.ResponseDescription ?: query.ResultDesc ?: "no description"}")
        }
        // ResponseCode only confirms that Daraja accepted the query. ResultCode
        // carries the checkout transaction outcome and must drive payment state.
        val resultCode = query.ResultCode?.content?.toIntOrNull()
        if (query.ResultCode != null && resultCode == null) {
            error("Daraja STK query returned an invalid ResultCode: ${query.ResultCode.content}")
        }
        VerifiedPayment(mpesaQueryStatus(resultCode))
    }.onFailure { error ->
        logger.warn("M-Pesa STK status query failed for checkout reference={}: {}", reference, error.message)
    }

    fun close() = http.close()
}

internal fun mpesaQueryStatus(resultCode: Int?): String = when (resultCode) {
    0 -> "success"
    1032 -> "abandoned"
    4999 -> "pending"
    null -> "pending"
    else -> "failed"
}
