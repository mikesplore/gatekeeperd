package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime

object PaystackClient {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.feature.payment.PaystackClient")
    private val baseUrl = "https://api.paystack.co"

    private val http = HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                }
            )
        }
        defaultRequest {
            header("Authorization", "Bearer ${AppConfig.paystackSecretKey}")
        }
    }

    /**
     * Initialize a Paystack transaction and return the payment link.
     */
    suspend fun initializePayment(
        email: String,
        amountNaira: BigDecimal,
        projectSlug: String,
        currency: String? = null,
        callbackUrl: String? = null
    ): Result<String> = initializePaymentWithReference(email, amountNaira, projectSlug, currency, callbackUrl)
        .map { it.second }

    suspend fun initializePaymentWithReference(
        email: String,
        amountNaira: BigDecimal,
        projectSlug: String,
        currency: String? = null,
        callbackUrl: String? = null,
        serviceId: java.util.UUID? = null
    ): Result<Pair<String, String>> {
        val amountKobo = (amountNaira * BigDecimal(100)).toLong()
        val request = PaystackInitializeRequest(
            email = email,
            amount = amountKobo,
            currency = currency,
            metadata = buildMap {
                put("project_slug", projectSlug)
                serviceId?.let { put("service_id", it.toString()) }
            },
            callbackUrl = callbackUrl
        )

        return try {
            val response = http.post("$baseUrl/transaction/initialize") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            val body = response.body<PaystackInitializeResponse>()
            if (body.status && body.data != null) {
                logger.info("Initialized Paystack payment for $email, ref=${body.data.reference}")
                Result.success(body.data.reference to body.data.authorization_url)
            } else {
                logger.error("Paystack initialize failed: ${body.message}")
                Result.failure(Exception(body.message))
            }
        } catch (e: Exception) {
            logger.error("Error initializing Paystack payment", e)
            Result.failure(e)
        }
    }

    suspend fun verifyTransaction(reference: String): Result<PaystackVerifyData> {
        return try {
            val response = http.get("$baseUrl/transaction/verify/$reference")
            val body = response.body<PaystackVerifyResponse>()
            if (body.status && body.data != null) {
                Result.success(body.data)
            } else {
                Result.failure(Exception(body.message.ifBlank { "Unable to verify payment" }))
            }
        } catch (e: Exception) {
            logger.error("Error verifying Paystack payment ref=$reference", e)
            Result.failure(e)
        }
    }

    fun close() {
        http.close()
    }
}
