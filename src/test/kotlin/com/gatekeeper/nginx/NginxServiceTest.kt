package com.gatekeeper.nginx

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class NginxServiceTest {

    private val service = NginxService(
        sitesAvailablePath = "/tmp/nginx-sites-available",
        sitesEnabledPath = "/tmp/nginx-sites-enabled",
        gatekeeperPort = 8080,
        sslCertPath = "/tmp/letsencrypt/live"
    )

    @Test
    fun `defaults to https upstream for port 443`() {
        val config = service.generateNginxConfig(
            slug = "acw",
            domain = "acw.example.com",
            appPort = 443,
            sslEnabled = true
        )

        assertContains(config, "proxy_pass https://127.0.0.1:443;")
        assertContains(config, "listen 443 ssl;")
    }

    @Test
    fun `uses explicit upstream scheme when provided`() {
        val config = service.generateNginxConfig(
            slug = "acw",
            domain = "acw.example.com",
            appPort = 3001,
            upstreamScheme = "http",
            sslEnabled = false
        )

        assertContains(config, "proxy_pass http://127.0.0.1:3001;")
        assertContains(config, "listen 80;")
    }

    @Test
    fun `rejects invalid upstream scheme`() {
        assertFailsWith<IllegalArgumentException> {
            service.generateNginxConfig(
                slug = "acw",
                domain = "acw.example.com",
                appPort = 3001,
                upstreamScheme = "ftp",
                sslEnabled = false
            )
        }
    }
}
