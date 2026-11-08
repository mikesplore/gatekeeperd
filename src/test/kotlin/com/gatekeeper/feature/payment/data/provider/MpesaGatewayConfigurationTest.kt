package com.gatekeeper.feature.payment.data.provider

import com.gatekeeper.config.AppSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpesaGatewayConfigurationTest {
    @Test
    fun `production callback must use https`() {
        val insecure = gateway(callbackUrl = "http://payments.example.test/api/mpesa/callback", environment = "production")
        val secure = gateway(callbackUrl = "https://payments.example.test/api/mpesa/callback", environment = "production")
        val sandbox = gateway(callbackUrl = "http://localhost:8080/api/mpesa/callback", environment = "sandbox")
        try {
            assertFalse(insecure.isConfigured())
            assertTrue(secure.isConfigured())
            assertTrue(sandbox.isConfigured())
        } finally {
            insecure.close()
            secure.close()
            sandbox.close()
        }
    }

    private fun gateway(callbackUrl: String, environment: String) = MpesaGateway(
        AppSettings.fromEnv(
            mapOf(
                "MPESA_CONSUMER_KEY" to "test-key",
                "MPESA_CONSUMER_SECRET" to "test-secret",
                "MPESA_SHORTCODE" to "123456",
                "MPESA_PASSKEY" to "test-passkey",
                "MPESA_CALLBACK_URL" to callbackUrl,
                "MPESA_CALLBACK_TOKEN" to "t".repeat(40),
                "MPESA_ENVIRONMENT" to environment
            ),
            loadDotenv = false
        ).payment
    )
}
