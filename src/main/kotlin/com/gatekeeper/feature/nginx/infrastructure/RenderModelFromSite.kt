package com.gatekeeper.feature.nginx.infrastructure

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.ResolvedCertificate
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.service.domain.repository.SiteRecord

class RenderModelFromSite(
    private val deploymentUpstreamResolver: DeploymentUpstreamResolver,
    private val certificateProvisioner: CertbotCertificateProvisioner
) {
    fun renderModelFromSite(
        slug: String,
        site: SiteRecord,

        ): NginxSiteRenderModel {
        val (upstreamHost, port) = if (site.upstreamKind == "DEPLOYMENT_MANAGED" || site.upstreamContainerPort != null) {
            if (site.upstreamKind == "DEPLOYMENT_MANAGED") {
                site.upstreamHost to (site.upstreamExplicitPort
                    ?: error("Site $slug has no persisted deployment upstream port"))
            } else {
                val target = site.serviceId?.let {
                    site.upstreamContainerPort?.let { containerPort ->
                        deploymentUpstreamResolver.resolveRuntime(it, containerPort, "production")
                    } ?: deploymentUpstreamResolver.resolveRuntime(it, "production")
                } ?: error("Could not resolve the active deployment upstream for managed site $slug")
                target.host to target.port
            }
        } else when (site.upstreamMode) {
            UpstreamMode.EXPLICIT_PORT -> site.upstreamHost to
                    (site.upstreamExplicitPort ?: error("Site $slug has no explicit upstream port"))

            UpstreamMode.DOCKER_DISCOVERY -> {
                val target = site.serviceId?.let { deploymentUpstreamResolver.resolve(it, "production") }
                    ?: error("Could not resolve the active deployment upstream for Docker site $slug")
                val containerName = target.containerName
                    ?: error("Active deployment runtime has no container name for Docker site $slug")
                target.host to target.port
            }
        }
        val certificate = when (site.certMode) {
            CertMode.AUTO_RESOLVE -> certificateProvisioner.resolveCertificateForDomain(site.domain)
            CertMode.EXPLICIT_PATH -> site.certExplicitPath?.let {
                ResolvedCertificate("explicit", it, it.replace("fullchain.pem", "privkey.pem"))
            } ?: error("Site $slug has no explicit certificate path")
        }
        val tls = when (site.tlsMode) {
            TlsMode.HTTP_ONLY -> TlsRenderMode.HTTP_ONLY
            TlsMode.HTTPS -> TlsRenderMode.HTTPS
            TlsMode.HTTPS_HTTP2 -> TlsRenderMode.HTTPS_HTTP2
        }
        return NginxSiteRenderModel(
            slug = slug,
            projectId = site.projectId,
            serviceId = site.serviceId,
            siteId = site.id,
            domain = site.domain,
            upstreamHost = upstreamHost,
            appPort = port,
            // Public TLS terminates at nginx; application containers normally speak HTTP.
            upstreamScheme = "http",
            tlsMode = tls,
            certificatePath = certificate?.certificatePath,
            certificateKeyPath = certificate?.privateKeyPath,
            upstreamMode = site.upstreamMode,
            certMode = site.certMode,
            gateEnabled = site.gateEnabled,
            bypassPaths = site.bypassPaths,
            upstreamKind = site.upstreamKind,
            customNginxLocations = site.customNginxLocations
        )
    }
}