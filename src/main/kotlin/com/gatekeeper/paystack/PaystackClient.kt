package com.gatekeeper.paystack

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.tables.Projects
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime

object PaystackClient {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.paystack.PaystackClient")
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
        callbackUrl: String? = null
    ): Result<String> {
        val amountKobo = (amountNaira * BigDecimal(100)).toLong()
        val request = PaystackInitializeRequest(
            email = email,
            amount = amountKobo,
            metadata = mapOf("project_slug" to projectSlug),
            callbackUrl = callbackUrl
        )

        return try {
            val response = http.post("$baseUrl/transaction/initialize") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            val body = response.body<PaystackInitializeResponse>()
            if (body.status && body.data != null) {
                // Store pending payment row
                val project = transaction {
                    Projects.selectAll().where { Projects.slug eq projectSlug }.singleOrNull()
                }
                if (project != null) {
                    PaymentRepository.create(
                        projectId = project[Projects.id],
                        paystackReference = body.data.reference,
                        authorizationUrl = body.data.authorization_url,
                        amount = amountNaira,
                        status = "pending",
                        rawWebhookPayload = null
                    )
                }
                logger.info("Initialized Paystack payment for $email, ref=${body.data.reference}")
                Result.success(body.data.authorization_url)
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