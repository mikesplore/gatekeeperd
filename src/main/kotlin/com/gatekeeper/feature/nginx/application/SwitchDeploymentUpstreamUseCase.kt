package com.gatekeeper.feature.nginx.application

import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.nginx.DeploymentUpstreamIncludes
import com.gatekeeper.nginx.NginxCommandRunner
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.nginx.ResolvedCertificate
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.nginx.logger
import com.gatekeeper.plugins.DistributedLock
import java.util.UUID

class SwitchDeploymentUpstreamUseCase(
    private val storage: NginxSiteStorage,
    private val commandRunner: NginxCommandRunner,
    private val cutoverProbe: CutoverProbe,
    private val siteRepository: SiteRepository
) {
    fun execute(serviceId: UUID, domain: String, tls: Boolean, host: String, port: Int): Boolean {
        val previous = storage.readUpstreamInclude(serviceId)
        val newUpstream = "server $host:$port max_fails=3 fail_timeout=10s;
"

        storage.writeUpstreamInclude(serviceId, newUpstream)
        if (!commandRunner.reload()) {
            if (previous != null) storage.writeUpstreamInclude(serviceId, previous)
            commandRunner.reload()
            return false
        }

        val healthy = cutoverProbe.throughLocalNginx(domain, tls)
        if (!healthy) {
            if (previous != null) storage.writeUpstreamInclude(serviceId, previous)
            commandRunner.reload()
            return false
        }

        siteRepository.updateDeploymentUpstream(serviceId, host, port)
        return true
    }

    fun verifyDeploymentRoute(domain: String, tls: Boolean): Boolean =
        DistributedLock.withLock("nginx-host-mutation") { localProbeRunner(domain, tls) }

    data class PostDrainRouteCheck(val candidateHealthy: Boolean, val previousRouteRestored: Boolean)

    fun verifyAfterDrain(
        serviceId: java.util.UUID,
        domain: String,
        tls: Boolean,
        previousPort: Int?
    ): PostDrainRouteCheck {
        return DistributedLock.withLock("nginx-host-mutation") {
            if (localProbeRunner(domain, tls)) return@withLock PostDrainRouteCheck(true, false)
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            runCatching {
                if (previousPort == null) Files.deleteIfExists(includes.file(serviceId).toPath())
                else includes.write(serviceId, includes.render(serviceId, previousPort))
                check(nginxTestRunner().valid && nginxReloadRunner())
                check(localProbeRunner(domain, tls))
            }.fold(
                onSuccess = { PostDrainRouteCheck(false, true) },
                onFailure = { error ->
                    logger.error(
                        "Post-drain route failed and rollback was unsuccessful for service {}",
                        serviceId,
                        error
                    )
                    PostDrainRouteCheck(false, false)
                }
            )
        }
    }

    fun restoreDeploymentUpstream(serviceId: java.util.UUID, port: Int?): Boolean =
        DistributedLock.withLock("nginx-host-mutation") {
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val previous = includes.file(serviceId).takeIf { it.isFile }?.readText()
            try {
                if (port == null) Files.deleteIfExists(includes.file(serviceId).toPath())
                else includes.write(serviceId, includes.render(serviceId, port))
                if (!nginxTestRunner().valid || !nginxReloadRunner()) {
                    restoreUpstreamInclude(includes, serviceId, previous)
                    return@withLock false
                }
                true
            } catch (error: Exception) {
                runCatching { restoreUpstreamInclude(includes, serviceId, previous) }
                logger.error("Unable to restore stable upstream for service {}", serviceId, error)
                false
            }
        }

    /** Route a managed project site to a ready candidate and reload nginx before reporting success. */
    fun switchDeploymentUpstream(serviceId: java.util.UUID, slug: String, host: String, port: Int): Boolean {
        val site = siteRepository.findByServiceId(serviceId) ?: return true
        val previousContent = inspectSite(slug).content
        val previousPort = site.upstreamExplicitPort
        val containerPort = site.upstreamContainerPort ?: port
        return switchDeploymentUpstream(serviceId, slug, host, containerPort, port)
    }

    fun switchDeploymentUpstream(
        serviceId: java.util.UUID,
        slug: String,
        host: String,
        containerPort: Int,
        hostPort: Int
    ): Boolean {
        require(host == "127.0.0.1") { "Deployment upstream must use loopback" }
        require(containerPort in 1..65535 && hostPort in 1..65535) { "Deployment upstream ports must be between 1 and 65535" }
        val site = siteRepository.findByServiceId(serviceId) ?: return true
        if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
            return DistributedLock.withLock("nginx-host-mutation") {
                val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
                val file = includes.file(serviceId)
                val previous = file.takeIf { it.isFile }?.readText()
                val candidate = includes.render(serviceId, hostPort)
                try {
                    includes.write(serviceId, candidate)
                    if (!nginxTestRunner().valid || !nginxReloadRunner() || !localProbeRunner(
                            site.domain,
                            site.tlsMode != TlsMode.HTTP_ONLY
                        )
                    ) {
                        restoreUpstreamInclude(includes, serviceId, previous)
                        return@withLock false
                    }
                    true
                } catch (error: Exception) {
                    runCatching { restoreUpstreamInclude(includes, serviceId, previous) }
                    logger.error("Stable upstream cutover failed for service {}", serviceId, error)
                    false
                }
            }
        }
        val previousContent = inspectSite(slug).content
        val previousPort = site.upstreamExplicitPort
        val certificate = when (site.certMode) {
            CertMode.AUTO_RESOLVE -> resolveCertificateForDomain(site.domain)
            CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let {
                ResolvedCertificate("explicit", it, it.replace("fullchain.pem", "privkey.pem"))
            } ?: return false
        }
        val tls = when (site.tlsMode) {
            TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
            TlsMode.HTTPS -> TlsRenderMode.HTTPS
            TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
        }
        val model = NginxSiteRenderModel(
            slug = slug,
            projectId = site.projectId,
            serviceId = site.serviceId,
            siteId = site.id,
            domain = site.domain,
            upstreamHost = host,
            appPort = hostPort,
            upstreamScheme = if (containerPort == 443) "https" else "http",
            tlsMode = tls,
            certificatePath = certificate?.certificatePath,
            certificateKeyPath = certificate?.privateKeyPath,
            upstreamMode = UpstreamMode.EXPLICIT_PORT,
            certMode = site.certMode,
            gateEnabled = site.gateEnabled,
            bypassPaths = site.bypassPaths,
            customNginxLocations = site.customNginxLocations
        )
        val config = generateNginxConfig(model)
        if (!enableProject(slug, config)) return false
        if (siteRepository.updateDeploymentUpstreamForSite(site.id, host, hostPort, containerPort) != null) return true
        previousContent?.let { restoreSiteConfiguration(slug, it) }
        previousPort?.let { siteRepository.updateDeploymentUpstreamForSite(site.id, site.upstreamHost, it) }
        return false
    }
}
