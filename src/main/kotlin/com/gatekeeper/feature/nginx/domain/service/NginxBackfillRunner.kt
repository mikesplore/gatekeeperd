package com.gatekeeper.feature.nginx.domain.service

import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.feature.certificate.data.persistence.ExposedCertificateRepository
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.nginx.model.ResolvedCertificate
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.infrastructure.NginxSiteBackfill
import com.gatekeeper.feature.nginx.infrastructure.NginxConfigRenderer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.presentation.BackfillDockerTarget
import com.gatekeeper.feature.nginx.presentation.BackfillProject
import com.gatekeeper.feature.nginx.presentation.NginxBackfillReport
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.service.data.persistence.ExposedSiteRepository
import com.gatekeeper.feature.project.data.persistence.ExposedProjectRepository
import com.gatekeeper.deployment.DeploymentApplicationService
import java.io.File

/** Manual production entry point. Call only after database and Docker are available. */
class NginxBackfillRunner(
    private val certificates: ExposedCertificateRepository = ExposedCertificateRepository(),
    private val projectRepository: ProjectRepository,
    private val siteRepository: SiteRepository,
    private val deploymentUpstreamResolver: DeploymentUpstreamResolver,
    private val resolveCertificateForDomain: (String) -> ResolvedCertificate?,
    private val renderer: NginxConfigRendererService
) {
    companion object {
        fun create(settings: AppSettings): NginxBackfillRunner {
            val sites = ExposedSiteRepository()
            val certificateRoot = File(settings.nginx.sslCertPath)
            fun resolveCertificate(domain: String): ResolvedCertificate? {
                val labels = domain.trimEnd('.').split('.').filter(String::isNotBlank)
                val candidates = listOf(domain) + (1 until labels.lastIndex).map { labels.drop(it).joinToString(".") }
                return candidates.firstNotNullOfOrNull { candidate ->
                    val directory = File(certificateRoot, candidate)
                    val fullchain = File(directory, "fullchain.pem")
                    val privateKey = File(directory, "privkey.pem")
                    if (fullchain.isFile && privateKey.isFile) {
                        ResolvedCertificate(candidate, fullchain.absolutePath, privateKey.absolutePath)
                    } else null
                }
            }

            return NginxBackfillRunner(
                projectRepository = ExposedProjectRepository(sites),
                siteRepository = sites,
                deploymentUpstreamResolver = DeploymentUpstreamResolver(sites, DeploymentApplicationService),
                resolveCertificateForDomain = ::resolveCertificate,
                renderer = NginxConfigRendererService(
                    NginxConfigRenderer(
                        gatekeeperUpstream = settings.nginx.gatekeeperAuthUpstream,
                        sslCertPath = settings.nginx.sslCertPath,
                        upstreamIncludeDirectory = settings.nginx.upstreamIncludeDirectory,
                        nginxValidation = NginxValidationService()
                    )
                )
            )
        }
    }

    fun run(
        sitesAvailablePath: String,
        dryRun: Boolean = false,
        docker: DockerGateway? = null,
        settings: AppSettings
    ): NginxBackfillReport {
        val report = NginxSiteBackfill(
            sitesAvailable = File(sitesAvailablePath),
            findProject = { slug ->
                projectRepository.findBySlug(slug)?.let {
                    BackfillProject(it.id, it.slug, null)
                }
            },
            findProjectById = { projectId ->
                projectRepository.findActiveById(projectId)?.let {
                    BackfillProject(it.id, it.slug, null)
                }
            },
            siteExists = siteRepository::existsForDomain,
            serviceIdForProject = siteRepository::findDefaultServiceId,
            expectedDockerTarget = { project ->
                val target = siteRepository.findByProjectId(project.id)?.serviceId?.let {
                    deploymentUpstreamResolver.resolve(
                        it,
                        "production"
                    )
                }
                val containerName = target?.containerName
                if (docker == null || target == null || containerName.isNullOrBlank()) null
                else BackfillDockerTarget(containerName, target.port)
            },
            autoCertificatePath = { domain -> resolveCertificateForDomain(domain)?.certificatePath },
            render = { model -> renderer.generateNginxConfig(model) },
            createSite = { project, model ->
                siteRepository.create(project.id, model)
                if (model.certMode == CertMode.AUTO_RESOLVE) {
                    resolveCertificateForDomain(model.domain)?.let { resolved ->
                        val certificate =
                            certificates.upsert(resolved.certificateDomain, null, null, "discovered")
                        siteRepository.linkCertificateForDomain(model.domain, certificate.id)
                    }
                }
            },
            deleteSite = { project -> siteRepository.deleteByProjectId(project.id) },
            dryRun = dryRun,
            log = ::println,
            selfDomainExclusion = NginxSelfDomainExclusion(settings.nginx.selfDomains)
        ).run()

        if (report.failedDiff.isNotEmpty()) {
            report.failedDiff.forEach { (slug, diff) -> println("$slug: $diff") }
        }
        return report
    }
}
