package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.MpesaInitiationGuard
import com.gatekeeper.plugins.Metrics
import com.gatekeeper.plugins.RedisService
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

/** Redis-backed quotas and a per-service/phone cooldown for public STK initiation. */
class MpesaPaymentProtection(
    private val settings: AppSettings.PaymentSettings,
    private val increment: (String, Int) -> Long = RedisService::incrementWithExpiry,
    private val reserveCooldown: (String, String, Int) -> Boolean = RedisService::setIfAbsent
) : MpesaInitiationGuard {
    override fun reserve(
        clientIp: String,
        serviceId: UUID,
        payerFingerprint: String
    ): MpesaInitiationGuard.Decision {
        try {
            val window = settings.mpesaRateLimitWindowSeconds
            val ipAttempts = increment("mpesa:init:ip:${digest(clientIp)}", window)
            val serviceAttempts = increment("mpesa:init:service:$serviceId", window)
            val phoneAttempts = increment("mpesa:init:phone:$payerFingerprint", window)
            if (ipAttempts > settings.mpesaRateLimitIpMax ||
                serviceAttempts > settings.mpesaRateLimitServiceMax ||
                phoneAttempts > settings.mpesaRateLimitPhoneMax
            ) {
                Metrics.increment("payment.mpesa.rate_limited")
                return MpesaInitiationGuard.Decision.Limited(window)
            }

            val cooldownKey = "mpesa:init:cooldown:$serviceId:$payerFingerprint"
            if (!reserveCooldown(cooldownKey, "reserved", settings.mpesaInitiationCooldownSeconds)) {
                Metrics.increment("payment.mpesa.rate_limited")
                return MpesaInitiationGuard.Decision.Limited(settings.mpesaInitiationCooldownSeconds)
            }
            Metrics.increment("payment.mpesa.initiation_allowed")
            return MpesaInitiationGuard.Decision.Allowed
        } catch (error: Exception) {
            Metrics.increment("payment.mpesa.rate_limit_unavailable")
            logger.error("M-Pesa initiation refused because Redis rate limiting is unavailable", error)
            throw RateLimitStoreUnavailable(error)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    class RateLimitStoreUnavailable(cause: Throwable) : RuntimeException("Rate limit storage unavailable", cause)

    private companion object {
        val logger = LoggerFactory.getLogger(MpesaPaymentProtection::class.java)
    }
}
