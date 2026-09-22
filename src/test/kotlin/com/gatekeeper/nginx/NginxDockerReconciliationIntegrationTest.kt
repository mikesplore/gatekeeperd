package com.gatekeeper.nginx

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.docker.DockerService
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Requires an explicitly supplied disposable Docker container. It never stops an
 * arbitrary host container during the normal test suite.
 */
class NginxDockerReconciliationIntegrationTest {
    @Test
    fun `stopped enabled container is reported as docker down`() {
        val containerName = System.getenv("GATEKEEPERD_DOCKER_INTEGRATION_CONTAINER") ?: return
        val docker = DockerService(AppConfig.dockerSocket)
        try {
            if (docker.containerHealth(containerName) != "running") return
            val root = Files.createTempDirectory("gk-docker-reconcile").toFile()
            val available = File(root, "sites-available").apply { mkdirs() }
            val enabled = File(root, "sites-enabled").apply { mkdirs() }
            val slug = "docker-integration"
            val config = "# fixture"
            File(available, slug).writeText(config)
            Files.createSymbolicLink(File(enabled, slug).toPath(), File(available, slug).toPath())
            val site = SiteRepository.SiteRecord(
                id = UUID.randomUUID(), projectId = UUID.randomUUID(), projectSlug = slug,
                domain = "docker-integration.example.com", upstreamHost = "127.0.0.1",
                upstreamMode = UpstreamMode.DOCKER_DISCOVERY, upstreamContainerName = containerName,
                upstreamExplicitPort = null, tlsMode = TlsMode.HTTP_ONLY, certMode = CertMode.AUTO_RESOLVE,
                certExplicitPath = null, gateEnabled = true, configVersion = 1,
                createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now(),
                reconciliationStatus = ReconciliationStatus.HEALTHY, lastNginxError = null,
                lastDockerError = null, lastReconciledAt = null
            )
            val reconciliation = NginxReconciliationService(
                available, enabled, { listOf(site) }, { config },
                NginxReconciliationService.dockerCheck(docker), { null }, persist = { _, _ -> }
            )

            docker.stopContainer(containerName)
            try {
                reconciliation.invalidateCache()
                assertEquals(ReconciliationStatus.DOCKER_DOWN, reconciliation.getSiteStatuses().results.single().status)
            } finally {
                docker.startContainer(containerName)
            }
        } finally {
            docker.close()
        }
    }
}
