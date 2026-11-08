package com.gatekeeper.feature.nginx.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CertbotDevelopmentPolicyTest {
    @Test
    fun `development reports Certbot disabled unless explicitly enabled`() {
        assertEquals(
            "Certificate installation, renewal, and removal are disabled in DEV. Enable GATEKEEPERD_NGINX_CERTBOT_ENABLED only when the restricted Certbot helper is installed.",
            devCertbotDisabledMessage(isDev = true, certbotEnabled = false)
        )
        assertNull(devCertbotDisabledMessage(isDev = true, certbotEnabled = true))
        assertNull(devCertbotDisabledMessage(isDev = false, certbotEnabled = false))
    }
}
