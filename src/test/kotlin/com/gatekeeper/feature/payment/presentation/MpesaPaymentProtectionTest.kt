package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.MpesaInitiationGuard
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpesaPaymentProtectionTest {
    @Test
    fun `reserves rate limits for ip service and phone then applies cooldown`() {
        val keys = mutableListOf<String>()
        var cooldownAvailable = true
        val protection = MpesaPaymentProtection(
            policy(),
            increment = { key, ttl -> keys += "$key:$ttl"; 1L },
            reserveCooldown = { key, value, ttl ->
                keys += "$key:$value:$ttl"
                cooldownAvailable.also { cooldownAvailable = false }
            }
        )
        val serviceId = UUID.randomUUID()

        assertIs<MpesaInitiationGuard.Decision.Allowed>(protection.reserve("192.0.2.5", serviceId, "phone-hash"))
        assertIs<MpesaInitiationGuard.Decision.Limited>(protection.reserve("192.0.2.5", serviceId, "phone-hash"))
        assertEquals(6, keys.count { it.startsWith("mpesa:init:") && !it.contains(":cooldown:") })
        assertTrue(keys.any { it.startsWith("mpesa:init:ip:") && !it.contains("192.0.2.5") })
        kotlin.test.assertTrue(keys.any { it.startsWith("mpesa:init:service:$serviceId:") })
        kotlin.test.assertTrue(keys.any { it.startsWith("mpesa:init:phone:phone-hash:") })
    }

    @Test
    fun `exceeding any dimension returns a limit decision`() {
        val protection = MpesaPaymentProtection(
            policy(),
            increment = { key, _ -> if (key.startsWith("mpesa:init:phone:")) 4L else 1L },
            reserveCooldown = { _, _, _ -> error("cooldown must not be acquired after a quota is exceeded") }
        )

        val result = protection.reserve("192.0.2.5", UUID.randomUUID(), "phone-hash")

        assertEquals(60, (result as MpesaInitiationGuard.Decision.Limited).retryAfterSeconds)
    }

    @Test
    fun `redis errors propagate so initiation can fail closed`() {
        val protection = MpesaPaymentProtection(policy(), increment = { _, _ -> error("redis unavailable") })

        assertFailsWith<MpesaPaymentProtection.RateLimitStoreUnavailable> {
            protection.reserve("192.0.2.5", UUID.randomUUID(), "phone-hash")
        }
    }

    private fun policy() = AppSettings.fromEnv(emptyMap(), loadDotenv = false).payment
}
