package com.gatekeeper.nginx

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import java.time.LocalDateTime

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
    fun `site render model preserves legacy output`() {
        val legacy = service.generateNginxConfig(
            slug = "acw",
            domain = "acw.example.com",
            appPort = 3001,
            upstreamScheme = "http",
            sslEnabled = false
        )
        val fromSite = service.generateNginxConfig(
            NginxSiteRenderModel(
                slug = "acw",
                domain = "acw.example.com",
                appPort = 3001,
                upstreamScheme = "http",
                tlsMode = TlsRenderMode.HTTP_ONLY
            )
        )
        assertEquals(legacy, fromSite)
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

    @Test
    fun `failed validation leaves existing site file and symlink unchanged`() {
        val root = Files.createTempDirectory("gk-nginx-enable").toFile()
        val available = File(root, "sites-available").apply { mkdirs() }
        val enabled = File(root, "sites-enabled").apply { mkdirs() }
        val slug = "acw"
        val existing = File(available, slug).apply { writeText("server {\n    listen 80;\n}\n") }
        val link = File(enabled, slug)
        Files.createSymbolicLink(link.toPath(), existing.toPath())

        val service = NginxService(
            sitesAvailablePath = available.absolutePath,
            sitesEnabledPath = enabled.absolutePath,
            gatekeeperPort = 8080,
            sslCertPath = root.resolve("certificates").absolutePath
        )

        assertFalse(service.enableProject(slug, "server {\n    listen 443;\n"))

        assertEquals("server {\n    listen 80;\n}\n", existing.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(existing.toPath(), Files.readSymbolicLink(link.toPath()))
        assertFalse(File(available, ".${slug}.staged").exists())
    }

    @Test
    fun `rejects dangerous nginx inputs before filesystem or command use`() {
        assertFailsWith<IllegalArgumentException> { requireValidHostname("example.com; touch /tmp/pwned") }
        assertFailsWith<IllegalArgumentException> {
            requireCertificatePath("/etc/letsencrypt/live/../../tmp/fullchain.pem", "/etc/letsencrypt/live")
        }
        assertFailsWith<IllegalArgumentException> { requireValidEmail("not-an-email") }
    }

    private fun isolatedService(root: File, reloads: MutableList<Boolean> = mutableListOf()): NginxService {
        val available = File(root, "sites-available").apply { mkdirs() }
        val enabled = File(root, "sites-enabled").apply { mkdirs() }
        return NginxService(
            sitesAvailablePath = available.absolutePath,
            sitesEnabledPath = enabled.absolutePath,
            sslCertPath = File(root, "certificates").absolutePath,
            nginxTestRunner = { NginxTestResult(true, 0, "stub", "now") },
            nginxReloadRunner = { reloads += true; true }
        )
    }

    @Test
    fun `successful activation writes file symlink hash and backup`() {
        val root = Files.createTempDirectory("gk-nginx-activation").toFile()
        val available = File(root, "sites-available").apply { mkdirs() }
        val enabled = File(root, "sites-enabled").apply { mkdirs() }
        val slug = "acw"
        val old = "old-config"
        File(available, slug).writeText(old)
        Files.createSymbolicLink(File(enabled, slug).toPath(), File(available, slug).toPath())
        val service = isolatedService(root)

        assertTrue(service.enableProject(slug, "new-config"))
        assertEquals("new-config", File(available, slug).readText())
        assertEquals(File(available, slug).toPath(), Files.readSymbolicLink(File(enabled, slug).toPath()))
        val backup = service.listBackups(slug).single()
        assertEquals("old-config", File(available, backup.name).readText())
        assertTrue(service.inspectSite(slug).managed)
    }

    @Test
    fun `reload failure restores previous file and symlink`() {
        val root = Files.createTempDirectory("gk-nginx-reload-failure").toFile()
        val available = File(root, "sites-available").apply { mkdirs() }
        val enabled = File(root, "sites-enabled").apply { mkdirs() }
        val slug = "acw"
        val existing = File(available, slug).apply { writeText("old-config") }
        Files.createSymbolicLink(File(enabled, slug).toPath(), existing.toPath())
        val service = NginxService(
            sitesAvailablePath = available.absolutePath,
            sitesEnabledPath = enabled.absolutePath,
            nginxTestRunner = { NginxTestResult(true, 0, "stub", "now") },
            nginxReloadRunner = { false }
        )

        assertFalse(service.enableProject(slug, "new-config"))
        assertEquals("old-config", existing.readText())
        assertEquals(existing.toPath(), Files.readSymbolicLink(File(enabled, slug).toPath()))
    }

    @Test
    fun `different slugs can activate concurrently without crossing files or backups`() {
        val root = Files.createTempDirectory("gk-nginx-concurrency").toFile()
        val available = File(root, "sites-available").apply { mkdirs() }
        val enabled = File(root, "sites-enabled").apply { mkdirs() }
        val service = isolatedService(root)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("alpha", "beta").map { slug ->
                executor.submit<Boolean> { service.enableProject(slug, "config-$slug") }
            }
            assertTrue(futures.all { it.get(10, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        assertEquals("config-alpha", File(available, "alpha").readText())
        assertEquals("config-beta", File(available, "beta").readText())
        assertEquals(File(available, "alpha").toPath(), Files.readSymbolicLink(File(enabled, "alpha").toPath()))
        assertEquals(File(available, "beta").toPath(), Files.readSymbolicLink(File(enabled, "beta").toPath()))
    }

    @Test
    fun `backfill reports migrated skipped and byte-diff failures`() {
        val root = Files.createTempDirectory("gk-nginx-backfill").toFile()
        val fixture = File(root, "sites-available").apply { mkdirs() }
        fun config(domain: String, port: Int) = """
            server {
                listen 80;
                server_name $domain;
                location / { proxy_pass http://127.0.0.1:$port; }
            }
        """.trimIndent() + "\n"
        File(fixture, "migrated").writeText(config("migrated.example.com", 3001))
        File(fixture, "failed").writeText(config("failed.example.com", 3002))
        File(fixture, "orphan").writeText(config("orphan.example.com", 3003))
        val projects = mapOf(
            "migrated" to BackfillProject(java.util.UUID.randomUUID(), "migrated", "migrated-container"),
            "failed" to BackfillProject(java.util.UUID.randomUUID(), "failed", "failed-container")
        )
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val report = NginxSiteBackfill(
            sitesAvailable = fixture,
            findProject = { projects[it] },
            expectedDockerPort = { project -> if (project.slug == "migrated") 3001 else 9999 },
            autoCertificatePath = { null },
            render = { model -> if (model.slug == "migrated") fixture.resolve(model.slug).readText() else "different" },
            createSite = { project, _ -> created += project.slug },
            deleteSite = { project -> deleted += project.slug }
        ).run()

        assertEquals(listOf("migrated"), report.migrated)
        assertEquals(listOf("orphan"), report.skippedNoProject)
        assertEquals(listOf("failed"), report.failedDiff.keys.toList())
        assertEquals(listOf("failed", "migrated"), created)
        assertEquals(listOf("failed"), deleted)
    }

    @Test
    fun `legacy renderer remains available for an unmigrated slug`() {
        val rendered = service.generateNginxConfig(
            slug = "unmigrated",
            domain = "unmigrated.example.com",
            appPort = 3001,
            upstreamScheme = "http",
            sslEnabled = false
        )
        assertContains(rendered, "server_name unmigrated.example.com;")
        assertContains(rendered, "proxy_pass http://127.0.0.1:3001;")
    }

    @Test
    fun `reconciliation evaluates disabled drift docker error dead and orphaned states`() {
        val root = Files.createTempDirectory("gk-reconcile").toFile()
        val available = File(root, "sites-available").apply { mkdirs() }
        val enabled = File(root, "sites-enabled").apply { mkdirs() }
        val slugs = listOf("healthy", "disabled", "drifted", "docker", "error", "dead")
        val sites = slugs.map { slug ->
            SiteRepository.SiteRecord(
                id = java.util.UUID.randomUUID(), projectId = java.util.UUID.randomUUID(), projectSlug = slug,
                domain = "$slug.example.com", upstreamHost = "127.0.0.1", upstreamMode = UpstreamMode.EXPLICIT_PORT,
                upstreamContainerName = null, upstreamExplicitPort = 3001, tlsMode = TlsMode.HTTP_ONLY,
                certMode = CertMode.AUTO_RESOLVE, certExplicitPath = null, gateEnabled = true, configVersion = 1,
                createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now(), reconciliationStatus = ReconciliationStatus.HEALTHY,
                lastNginxError = null, lastDockerError = null, lastReconciledAt = null
            )
        }
        File(available, "healthy").writeText("healthy")
        File(available, "disabled").writeText("disabled")
        File(available, "drifted").writeText("on-disk")
        File(available, "docker").writeText("docker")
        File(available, "error").writeText("error")
        File(available, "orphan").writeText("orphan")
        listOf("healthy", "drifted", "docker", "error").forEach {
            Files.createSymbolicLink(File(enabled, it).toPath(), File(available, it).toPath())
        }
        val report = NginxReconciliationService(
            available, enabled, { sites },
            renderExpected = { site -> if (site.projectSlug == "drifted") "expected" else File(available, site.projectSlug!!).readText() },
            dockerCheck = { if (it.projectSlug == "docker") "container is not running" else null },
            nginxTest = { "/etc/nginx/sites-available/error: syntax error" },
            persist = { _, _ -> }
        ).reconcile()

        val states = report.results.associate { it.slug to it.status }
        assertEquals(ReconciliationStatus.HEALTHY, states["healthy"])
        assertEquals(ReconciliationStatus.DISABLED, states["disabled"])
        assertEquals(ReconciliationStatus.DRIFTED, states["drifted"])
        assertEquals(ReconciliationStatus.DOCKER_DOWN, states["docker"])
        assertEquals(ReconciliationStatus.ERROR, states["error"])
        assertEquals(ReconciliationStatus.DEAD_CONFIG, states["dead"])
        assertEquals(listOf("orphan"), report.orphanedFiles)
    }
}
