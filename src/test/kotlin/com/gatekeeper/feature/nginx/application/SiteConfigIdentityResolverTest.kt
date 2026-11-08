package com.gatekeeper.feature.nginx.application

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SiteConfigIdentityResolverTest {
    @Test
    fun `resolves project slug to the physical nginx filename from ownership markers`() {
        val root = Files.createTempDirectory("nginx-site-identity")
        val available = Files.createDirectories(root.resolve("sites-available"))
        val enabled = Files.createDirectories(root.resolve("sites-enabled"))
        val projectId = UUID.randomUUID()
        val siteId = UUID.randomUUID()
        val site = siteRecord(projectId, siteId, "weusifix-weusifix-mikesplore-tech")
        Files.writeString(
            available.resolve("weusifix.mikesplore.tech"),
            """
                # gatekeeperd:project_id:$projectId
                # gatekeeperd:site_id:$siteId
                server {
                    server_name weusifix.mikesplore.tech;
                }
            """.trimIndent()
        )

        val resolved = SiteConfigIdentityResolver(available.toString(), enabled.toString()).resolve(site)

        assertEquals("weusifix.mikesplore.tech", resolved)
    }

    private fun siteRecord(projectId: UUID, siteId: UUID, projectSlug: String) = SiteRecord(
        id = siteId,
        projectId = projectId,
        domain = "weusifix.mikesplore.tech",
        upstreamHost = "127.0.0.1",
        upstreamMode = UpstreamMode.EXPLICIT_PORT,
        upstreamExplicitPort = 32016,
        tlsMode = TlsMode.HTTPS,
        certMode = CertMode.AUTO_RESOLVE,
        certExplicitPath = null,
        gateEnabled = true,
        configVersion = 1,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        reconciliationStatus = ReconciliationStatus.HEALTHY,
        lastNginxError = null,
        lastDockerError = null,
        lastReconciledAt = null,
        projectSlug = projectSlug
    )
}
