package com.gatekeeper.feature.nginx.domain.service

import com.gatekeeper.feature.nginx.model.DEFAULT_GATEKEEPER_BYPASS_PATHS
import com.gatekeeper.feature.nginx.infrastructure.NginxConfigRenderer
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocation

class NginxConfigRenderer {
    fun generateNginxConfig(
        site: NginxSiteRenderModel
    ): String = generateNginxConfig(
        slug = site.slug,
        domain = site.domain,
        appPort = site.appPort,
        upstreamScheme = site.upstreamScheme,
        sslEnabled = site.tlsMode != TlsRenderMode.HTTP_ONLY,
        sslCertificatePath = site.certificatePath,
        sslCertificateKeyPath = site.certificateKeyPath,
        upstreamHost = site.upstreamHost,
        http2 = site.tlsMode == TlsRenderMode.HTTPS_HTTP2,
        gateEnabled = site.gateEnabled,
        bypassPaths = site.bypassPaths,
        projectId = site.projectId,
        serviceId = site.serviceId,
        siteId = site.siteId,
        upstreamKind = site.upstreamKind,
        customNginxLocations = site.customNginxLocations
    )

    fun generateNginxConfig(
        slug: String,
        domain: String,
        appPort: Int,
        upstreamScheme: String? = null,
        sslEnabled: Boolean,
        sslCertificatePath: String? = null,
        sslCertificateKeyPath: String? = null,
        upstreamHost: String = "127.0.0.1",
        http2: Boolean = true,
        gateEnabled: Boolean = true,
        bypassPaths: List<String> = DEFAULT_GATEKEEPER_BYPASS_PATHS,
        projectId: java.util.UUID? = null,
        serviceId: java.util.UUID? = null,
        siteId: java.util.UUID? = null,
        upstreamKind: String? = null,
        customNginxLocations: List<NginxCustomLocation> = emptyList()
    ): String = NginxConfigRenderer().render(
        slug, domain, appPort, upstreamScheme, sslEnabled, sslCertificatePath, sslCertificateKeyPath,
        upstreamHost, http2, gateEnabled, bypassPaths, projectId, serviceId, siteId, upstreamKind, customNginxLocations
    )

    fun normalizeUpstreamScheme(appPort: Int, upstreamScheme: String?): String {
        val normalized = upstreamScheme?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            return if (appPort == 443) "https" else "http"
        }

        require(normalized == "http" || normalized == "https") {
            "upstreamScheme must be either http or https"
        }
        return normalized
    }
}