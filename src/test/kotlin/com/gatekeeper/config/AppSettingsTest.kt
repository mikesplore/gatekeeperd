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
}
