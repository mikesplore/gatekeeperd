package com.gatekeeper.nginx

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NginxServiceTest {

    private fun newService(sslCertPath: String): NginxService =
        NginxService(
            sitesAvailablePath = "/tmp/nginx-sites-available",
            sitesEnabledPath = "/tmp/nginx-sites-enabled",
            gatekeeperPort = 8080,
            sslCertPath = sslCertPath
        )

    private val service = NginxService(
        sitesAvailablePath = "/tmp/nginx-sites-available",
        sitesEnabledPath = "/tmp/nginx-sites-enabled",
        gatekeeperPort = 8080,
        sslCertPath = Files.createTempDirectory("gk-letsencrypt-live").toFile().absolutePath
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

    @Test
    fun `resolves exact domain certificate when present`() {
        val liveDir = Files.createTempDirectory("gk-letsencrypt-live").toFile()
        val domainDir = File(liveDir, "acw.example.com").apply { mkdirs() }
        File(domainDir, "fullchain.pem").writeText("dummy")
        File(domainDir, "privkey.pem").writeText("dummy")

        val svc = newService(liveDir.absolutePath)
        val resolved = svc.resolveCertificateForDomain("acw.example.com")

        assertEquals("acw.example.com", resolved?.certificateDomain)
        assertEquals("${liveDir.absolutePath}/acw.example.com/fullchain.pem", resolved?.certificatePath)
        assertEquals("${liveDir.absolutePath}/acw.example.com/privkey.pem", resolved?.privateKeyPath)
    }

    @Test
    fun `resolves parent domain certificate for subdomain when present`() {
        val liveDir = Files.createTempDirectory("gk-letsencrypt-live").toFile()
        val parentDir = File(liveDir, "example.com").apply { mkdirs() }
        File(parentDir, "fullchain.pem").writeText("dummy")
        File(parentDir, "privkey.pem").writeText("dummy")

        val svc = newService(liveDir.absolutePath)
        val resolved = svc.resolveCertificateForDomain("acw.example.com")

        assertEquals("example.com", resolved?.certificateDomain)
    }

    @Test
    fun `uses requested certificateDomain when provided`() {
        val liveDir = Files.createTempDirectory("gk-letsencrypt-live").toFile()
        val dir = File(liveDir, "shared.example.com").apply { mkdirs() }
        File(dir, "fullchain.pem").writeText("dummy")
        File(dir, "privkey.pem").writeText("dummy")

        val svc = newService(liveDir.absolutePath)
        val resolved = svc.resolveCertificateForDomain(
            domain = "acw.example.com",
            requestedCertificateDomain = "shared.example.com"
        )

        assertEquals("shared.example.com", resolved?.certificateDomain)
    }
}
