package com.gatekeeper.customer

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.integrations.ResendClient
import com.gatekeeper.plugins.RedisService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Short-lived, email-verified access to customer-owned billing and service information. */
class CustomerSupportAccessService(
    private val customers: CustomerRepository,
    private val projects: ProjectRepository,
    private val resend: ResendClient,
    auth: AppSettings.AuthSettings
) {
    private val hmacKey = SecretKeySpec(auth.jwtSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    /** Always returns an opaque challenge id so callers cannot infer whether an email is registered. */
    suspend fun requestCode(email: String): String {
        val normalizedEmail = email.trim().lowercase()
        val challengeId = UUID.randomUUID().toString()
        val rateKey = "customer-support:request:${digest("email:$normalizedEmail")}" 
        if (RedisService.incrementWithExpiry(rateKey, REQUEST_WINDOW_SECONDS) > REQUEST_LIMIT) return challengeId

        val customer = customers.findByContactEmail(normalizedEmail) ?: return challengeId
        if (projects.findByCustomerId(customer.id).isEmpty()) return challengeId

        val code = secureRandom.nextInt(CODE_RANGE).toString().padStart(CODE_LENGTH, '0')
        val emailDigest = digest("email:$normalizedEmail")
        val codeDigest = digest("code:$challengeId:$code")
        val challengeKey = challengeKey(challengeId)
        RedisService.setRequired(challengeKey, "${customer.id}|$emailDigest|$codeDigest", CODE_TTL_SECONDS)
        if (!resend.sendCustomerAccessCode(normalizedEmail, code)) {
            RedisService.delete(challengeKey)
        }
        return challengeId
    }

    fun verifyCode(challengeId: String, code: String): String? {
        if (!challengeId.matches(UUID_PATTERN) || !code.matches(CODE_PATTERN)) return null
        val value = RedisService.getRequired(challengeKey(challengeId)) ?: return null
        val attemptKey = "customer-support:attempts:$challengeId"
        if (RedisService.incrementWithExpiry(attemptKey, CODE_TTL_SECONDS) > CODE_ATTEMPT_LIMIT) {
            RedisService.delete(challengeKey(challengeId))
            return null
        }

        val fields = value.split('|')
        if (fields.size != 3) return null
        val customerId = runCatching { UUID.fromString(fields[0]) }.getOrNull() ?: return null
        val expectedCode = digest("code:$challengeId:$code")
        if (!MessageDigest.isEqual(expectedCode.toByteArray(), fields[2].toByteArray())) return null

        val customer = customers.findById(customerId) ?: return null
        val currentEmail = customer.contactEmail?.trim()?.lowercase() ?: return null
        if (digest("email:$currentEmail") != fields[1] || projects.findByCustomerId(customerId).isEmpty()) return null

        if (!RedisService.compareAndDeleteRequired(challengeKey(challengeId), value)) return null
        RedisService.delete(attemptKey)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { secureRandom.nextBytes(it) })
        RedisService.setRequired(sessionKey(token), customerId.toString(), SESSION_TTL_SECONDS)
        return token
    }

    fun customerIdForSession(token: String?): UUID? {
        if (token.isNullOrBlank() || token.length > 128) return null
        return RedisService.getRequired(sessionKey(token))?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    fun revokeSession(token: String?) {
        if (!token.isNullOrBlank() && token.length <= 128) RedisService.delete(sessionKey(token))
    }

    private fun challengeKey(id: String) = "customer-support:challenge:$id"
    private fun sessionKey(token: String) = "customer-support:session:${digest("session:$token")}" 

    private fun digest(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SESSION_COOKIE = "gk_customer_session"
        const val SESSION_TTL_SECONDS = 8 * 60 * 60
        const val CODE_LENGTH = 6
        private const val REQUEST_WINDOW_SECONDS = 15 * 60
        private const val REQUEST_LIMIT = 3
        private const val CODE_TTL_SECONDS = 5 * 60
        private const val CODE_ATTEMPT_LIMIT = 5
        private const val CODE_RANGE = 1_000_000
        private val CODE_PATTERN = Regex("^[0-9]{6}$")
        private val UUID_PATTERN = Regex("^[0-9a-fA-F-]{36}$")
        private val secureRandom = SecureRandom()
    }
}
