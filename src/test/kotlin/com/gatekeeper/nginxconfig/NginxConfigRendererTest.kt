package com.gatekeeper.nginx

import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.NginxConfigRenderer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NginxConfigRendererTest {
    @Test
    fun `gate auth bypass and paywall use the configured upstream`() {
        val config = NginxConfigRenderer(
            gatekeeperUpstream = "gatekeeperd_backend",
            nginxValidation = NginxValidationService()
        ).render("service", "service.example.test", 8081, sslEnabled = false)

        assertTrue(config.contains("proxy_pass http://gatekeeperd_backend/api/gate/;"))
        assertTrue(config.contains("proxy_pass http://gatekeeperd_backend/api/gate/auth"))
        assertTrue(config.contains("proxy_pass http://gatekeeperd_backend;"))
        assertTrue(config.contains("auth_request /gatekeeper-auth-service;"))
        assertFalse(config.contains("127.0.0.1:8080"))
    }

    @Test
    fun `renderer rejects unsafe auth upstream syntax`() {
        assertFailsWith<IllegalArgumentException> {
            NginxConfigRenderer(gatekeeperUpstream = "http://untrusted/path", nginxValidation = NginxValidationService())
        }
    }
}
