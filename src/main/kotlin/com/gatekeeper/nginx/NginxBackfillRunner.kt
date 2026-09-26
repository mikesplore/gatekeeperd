package com.gatekeeper.nginx

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.repositories.CertificateRepository
import com.gatekeeper.docker.DockerService
import com.gatekeeper.deployment.DeploymentApplicationService
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
                        BackfillProject(it.id, it.slug, null)
                    }
                },
                findProjectById = { projectId ->
                    ProjectRepository.findActiveById(projectId)?.let {
                        BackfillProject(it.id, it.slug, null)
                    }
                },
                siteExists = SiteRepository::existsForProject,
                expectedDockerTarget = { project ->
                    val target = DeploymentUpstreamResolver.resolve(project.id, "production")
                    val containerName = target?.containerName
                    if (docker == null || target == null || containerName.isNullOrBlank()) null
                    else BackfillDockerTarget(containerName, target.port)
                },
                autoCertificatePath = { domain -> nginx.resolveCertificateForDomain(domain)?.certificatePath },
                render = { model -> nginx.generateNginxConfig(model) },
                createSite = { project, model ->
                    val site = SiteRepository.create(project.id, model)
                    if (model.certMode == com.gatekeeper.db.tables.CertMode.AUTO_RESOLVE) {
                        nginx.resolveCertificateForDomain(model.domain)?.let { resolved ->
                            val certificate =
                                CertificateRepository.upsert(resolved.certificateDomain, null, null, "discovered")
                            SiteRepository.linkCertificate(project.id, certificate.id)
                        }
                    }
                },
                deleteSite = { project -> SiteRepository.deleteByProjectId(project.id) },
                dryRun = dryRun,
                log = ::println
            ).run()
            if (report.failedDiff.isNotEmpty()) {
                report.failedDiff.forEach { (slug, diff) -> println("$slug: $diff") }
            }
            return report
        } finally {
            docker?.close()
        }
    }
}
