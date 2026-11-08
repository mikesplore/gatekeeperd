package com.gatekeeper.nginx

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.application.SiteConfigIdentityResolver
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SiteConfigIdentityResolverTest {
    @Test
    fun `resolves managed config by service identity when filename differs from site slug`() {
        val tempDir = Files.createTempDirectory("site-config-identity-test")
        val available = Files.createDirectories(tempDir.resolve("available"))
        val enabled = Files.createDirectories(tempDir.resolve("enabled"))
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()
        val site = site(projectId, serviceId)
        Files.writeString(
            available.resolve("weusifix"),
            """# gatekeeperd:project_id:$projectId
# gatekeeperd:service_id:$serviceId
server { server_name ${site.domain}; }
"""
        )
        Files.createSymbolicLink(enabled.resolve("weusifix"), available.resolve("weusifix"))

        assertEquals("weusifix", SiteConfigIdentityResolver(available.toString(), enabled.toString()).resolve(site))
    }

    private fun site(projectId: UUID, serviceId: UUID) = SiteRecord(
        id = UUID.randomUUID(), projectId = projectId, domain = "weusifix.mikesplore.tech",
        upstreamHost = "127.0.0.1", upstreamMode = UpstreamMode.EXPLICIT_PORT, upstreamExplicitPort = 32016,
        tlsMode = TlsMode.HTTPS, certMode = CertMode.AUTO_RESOLVE, certExplicitPath = null,
        gateEnabled = true, configVersion = 1, createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now(),
        reconciliationStatus = ReconciliationStatus.HEALTHY, lastNginxError = null, lastDockerError = null,
        lastReconciledAt = null, projectSlug = "weusifix-weusifix-mikesplore-tech", serviceId = serviceId
    )
}
