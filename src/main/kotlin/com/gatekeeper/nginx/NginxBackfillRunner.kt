package com.gatekeeper.nginx

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.repositories.CertificateRepository
import com.gatekeeper.docker.DockerService
import java.io.File

/** Manual production entry point. Call only after database and Docker are available. */
object NginxBackfillRunner {
    fun run(sitesAvailablePath: String = AppConfig.nginxSitesAvailablePath, dryRun: Boolean = false): NginxBackfillReport {
        val nginx = NginxService()
        val docker = runCatching { DockerService(AppConfig.dockerSocket) }.getOrNull()
        try {
            val report = NginxSiteBackfill(
                sitesAvailable = File(sitesAvailablePath),
                findProject = { slug ->
                    ProjectRepository.findBySlug(slug)?.let {
                        BackfillProject(it.id, it.slug, it.containerName)
                    }
                },
                expectedDockerPort = { project ->
                    val containerName = extractConfiguredContainerName(project.containerName)
                    if (docker == null || containerName == null) null
                    else parsePublishedHostPorts(docker.getContainer(containerName)?.ports.orEmpty()).singleOrNull()
                },
                autoCertificatePath = { domain -> nginx.resolveCertificateForDomain(domain)?.certificatePath },
                render = { model -> nginx.generateNginxConfig(model) },
                createSite = { project, model ->
                    val site = SiteRepository.create(project.id, model)
                    if (model.certMode == com.gatekeeper.db.tables.CertMode.AUTO_RESOLVE) {
                        nginx.resolveCertificateForDomain(model.domain)?.let { resolved ->
                            val certificate = CertificateRepository.upsert(resolved.certificateDomain, null, null, "discovered")
                            SiteRepository.linkCertificate(project.id, certificate.id)
                        }
                    }
                },
                deleteSite = { project -> SiteRepository.deleteByProjectId(project.id) },
                dryRun = dryRun,
                log = ::println
            ).run()
            println("nginx backfill summary: migrated=${report.migratedCount}, skipped=${report.skippedCount}, failed=${report.failedCount}")
            if (report.failedDiff.isNotEmpty()) {
                report.failedDiff.forEach { (slug, diff) -> println("$slug: $diff") }
            }
            return report
        } finally {
            docker?.close()
        }
    }
}
