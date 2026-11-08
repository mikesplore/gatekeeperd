package com.gatekeeper.feature.nginx.application

import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.feature.nginx.domain.port.CertificateProvisioner
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamIncludes
import com.gatekeeper.feature.nginx.model.ResolvedCertificate
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy

class ConvertSiteToDeploymentManagedUseCase(
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams",
    private val nginxTestRunner: () -> NginxTestResult,
    private val siteConfig: ApplySiteConfigUseCase,
    private val renderer: NginxConfigRendererService,
    private val cutoverProbe: CutoverProbe,
    private val nginxSiteStorage: NginxSiteStorage,
    private val inspectSite: InspectSiteConfigUseCase,
    private val siteRepository: SiteRepository,
    private val certificateProvisioner: CertificateProvisioner,
    private val verifyLocalRoute: Boolean = true,
    private val localProbeRunner: (String, Boolean) -> Boolean = { domain, tls ->
        cutoverProbe.throughLocalNginx(
            domain,
            tls
        )
    },
) {
    fun execute(
        site: SiteRecord,
        hostPort: Int
    ): Boolean {
        val serviceId = site.serviceId ?: return false
        val slug = site.projectSlug ?: return false
        require(hostPort in 1..65535)
        return DistributedLock.withLock("nginx-host-mutation") {
            // A site record can outlive its nginx file (for example after manual
            // cleanup). The saved SiteRecord is enough to render a fresh config;
            // preserve the old file only when it is still present for rollback.
            val previousSite = inspectSite.inspectSite(slug).content
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val includeFile = includes.file(serviceId)
            val previousInclude = includeFile.takeIf { it.isFile }?.readText()
            val target = includes.render(serviceId, hostPort)
            val certificate = when (site.certMode) {
                CertMode.AUTO_RESOLVE -> certificateProvisioner.resolveCertificateForDomain(site.domain)
                CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let {
                    ResolvedCertificate(
                        "explicit",
                        it,
                        it.replace("fullchain.pem", "privkey.pem")
                    )
                }
                    ?: return@withLock false
            }
            val model = NginxSiteRenderModel(
                slug = slug, projectId = site.projectId, serviceId = serviceId, siteId = site.id,
                domain = site.domain, upstreamHost = "127.0.0.1", appPort = site.upstreamExplicitPort ?: hostPort,
                upstreamScheme = "http",
                tlsMode = when (site.tlsMode) {
                    TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
                    TlsMode.HTTPS -> TlsRenderMode.HTTPS
                    TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
                }, certificatePath = certificate?.certificatePath, certificateKeyPath = certificate?.privateKeyPath,
                upstreamMode = site.upstreamMode, certMode = site.certMode, gateEnabled = site.gateEnabled,
                bypassPaths = site.bypassPaths, upstreamKind = "DEPLOYMENT_MANAGED",
                customNginxLocations = site.customNginxLocations
            )
            try {
                includes.write(serviceId, target)
                check(nginxTestRunner().valid) { "nginx -t failed with the service upstream include" }
                check(
                    siteConfig.enableProject(
                        slug,
                        renderer.generateNginxConfig(model)
                    )
                ) { "Unable to activate stable upstream site config" }
                if (verifyLocalRoute) {
                    check(
                        localProbeRunner(
                            site.domain,
                            site.tlsMode != TlsMode.HTTP_ONLY
                        )
                    ) { "Stable upstream probe failed after nginx reload" }
                } else {
                    nginxServiceLogger.info(
                        "Skipping live-route probe while converting site {} in development mode",
                        site.id
                    )
                }
                check(
                    siteRepository.setUpstreamKind(
                        site.id,
                        "DEPLOYMENT_MANAGED"
                    ) != null
                ) { "Unable to persist deployment-managed site kind" }
                true
            } catch (error: Exception) {
                runCatching {
                    if (previousSite != null) nginxSiteStorage.restoreSiteConfiguration(slug, previousSite)
                    else nginxSiteStorage.removeSite(slug)
                }
                runCatching { nginxSiteStorage.restoreUpstreamInclude(includes, serviceId, previousInclude) }
                nginxServiceLogger.error("Unable to convert site {} to deployment-managed upstream", site.id, error)
                false
            }
        }
    }
}
