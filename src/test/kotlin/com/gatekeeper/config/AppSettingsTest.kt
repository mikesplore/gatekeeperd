package com.gatekeeper.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppSettingsTest {
    @Test
    fun `environment overrides dotenv values`() {
        val settings = AppSettings.fromEnv(
            mapOf(
                "DB_URL" to "jdbc:test",
                "PORT" to "9090"
            )
        )

        assertEquals("jdbc:test", settings["DB_URL"])
        assertEquals("9090", settings["PORT"])
    }

    @Test
    fun `blank values are not exposed`() {
        assertEquals(null, AppSettings.fromEnv(mapOf("EMPTY_SETTING" to "  "))["EMPTY_SETTING"])
    }

    @Test
    fun `fail mode defaults closed`() {
        assertEquals(AppSettings.FailMode.CLOSED, AppSettings.fromEnv(emptyMap(), loadDotenv = false).application.failMode)
    }

    @Test
    fun `fail mode accepts explicit open and closed values case insensitively`() {
        assertEquals(AppSettings.FailMode.OPEN, AppSettings.fromEnv(mapOf("FAIL_MODE" to "OPEN")).application.failMode)
        assertEquals(AppSettings.FailMode.CLOSED, AppSettings.fromEnv(mapOf("FAIL_MODE" to "closed")).application.failMode)
    }

    @Test
    fun `invalid fail mode is rejected`() {
        assertFailsWith<IllegalStateException> {
            AppSettings.fromEnv(mapOf("FAIL_MODE" to "sometimes")).application
        }
    }

    @Test
    fun `mpesa rate limits have safe defaults and reject invalid thresholds`() {
        val defaults = AppSettings.fromEnv(emptyMap(), loadDotenv = false).payment
        assertEquals(60, defaults.mpesaRateLimitWindowSeconds)
        assertEquals(120, defaults.mpesaRateLimitIpMax)
        assertEquals(30, defaults.mpesaRateLimitServiceMax)
        assertEquals(3, defaults.mpesaRateLimitPhoneMax)
        assertEquals(60, defaults.mpesaInitiationCooldownSeconds)

        assertFailsWith<IllegalArgumentException> {
            AppSettings.fromEnv(mapOf("MPESA_RATE_LIMIT_PHONE_MAX" to "0"), loadDotenv = false).payment
        }
    }

    @Test
    fun `mpesa shortcode uses canonical variable and preserves legacy fallback`() {
        val canonical = AppSettings.fromEnv(mapOf("MPESA_SHORT_CODE" to "123456"), loadDotenv = false)
        val legacy = AppSettings.fromEnv(mapOf("MPESA_SHORTCODE" to "654321"), loadDotenv = false)
        val both = AppSettings.fromEnv(
            mapOf("MPESA_SHORT_CODE" to "123456", "MPESA_SHORTCODE" to "654321"),
            loadDotenv = false
        )

        assertEquals("123456", canonical.payment.mpesaShortCode)
        assertEquals("654321", legacy.payment.mpesaShortCode)
        assertEquals("123456", both.payment.mpesaShortCode)
    }

    @Test
    fun `development nginx host operations are explicitly opt in`() {
        val settings = AppSettings.fromEnv(
            mapOf(
                "GATEKEEPERD_ENVIRONMENT" to "DEV",
                "GATEKEEPERD_SELF_DOMAIN" to "gatekeeper.test"
            )
        )

        assertEquals(false, settings.nginx.hostOperationsEnabled)
        assertEquals(false, settings.nginx.certbotEnabled)
        assertEquals(false, settings.nginx.deploymentUpstreamsEnabled)
        assertEquals("nginx/sites-available", settings.nginx.sitesAvailablePath)
        assertEquals("nginx/sites-enabled", settings.nginx.sitesEnabledPath)
        assertEquals("nginx/conf.d/gatekeeperd-upstreams", settings.nginx.upstreamIncludeDirectory)
        assertEquals("nginx/custom-certs", settings.nginx.sslCertPath)
        assertEquals("nginx/nginx.conf", settings.nginx.developmentConfigPath)
    }

    @Test
    fun `development nginx host settings can be enabled without sudo`() {
        val settings = AppSettings.fromEnv(
            mapOf(
                "GATEKEEPERD_ENVIRONMENT" to "DEV",
                "GATEKEEPERD_NGINX_HOST_SUDO" to "true",
                "GATEKEEPERD_NGINX_CERTBOT_ENABLED" to "true",
                "GATEKEEPERD_SELF_DOMAIN" to "gatekeeper.test"
            )
        )

        assertEquals(true, settings.nginx.hostOperationsEnabled)
        assertEquals(true, settings.nginx.certbotEnabled)
    }

    @Test
    fun `production requires an explicit nginx auth upstream`() {
        assertFailsWith<IllegalArgumentException> {
            AppSettings.fromEnv(
                mapOf("GATEKEEPERD_ENVIRONMENT" to "PROD", "GATEKEEPERD_SELF_DOMAIN" to "gatekeeper.test"),
                loadDotenv = false
            ).nginx
        }
    }

    @Test
    fun `nginx auth upstream is validated and configured centrally`() {
        val settings = AppSettings.fromEnv(
            mapOf(
                "GATEKEEPERD_ENVIRONMENT" to "PROD",
                "GATEKEEPERD_SELF_DOMAIN" to "gatekeeper.test",
                "GATEKEEPER_NGINX_AUTH_UPSTREAM" to "gatekeeperd_backend"
            ),
            loadDotenv = false
        )
        assertEquals("gatekeeperd_backend", settings.nginx.gatekeeperAuthUpstream)
        assertEquals(false, AppSettings.fromEnv(emptyMap(), loadDotenv = false).docker.allowPublicPortBindings)
    }

    @Test
    fun `nginx auth upstream rejects unsafe syntax and invalid ports`() {
        for (invalid in listOf("http://backend", "backend/path", "backend:99999", "")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                AppSettings.fromEnv(
                    mapOf(
                        "GATEKEEPERD_ENVIRONMENT" to "PROD",
                        "GATEKEEPERD_SELF_DOMAIN" to "gatekeeper.test",
                        "GATEKEEPER_NGINX_AUTH_UPSTREAM" to invalid
                    ),
                    loadDotenv = false
                ).nginx
            }
        }
    }
}
