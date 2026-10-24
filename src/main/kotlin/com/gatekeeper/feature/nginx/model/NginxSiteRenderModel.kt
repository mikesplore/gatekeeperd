package com.gatekeeper.feature.nginx.model

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocation
import java.util.UUID

data class NginxSiteRenderModel(
    val slug: String,
    val projectId: UUID? = null,
    val domain: String,
    val upstreamHost: String = "127.0.0.1",
    val appPort: Int,
    val upstreamScheme: String,
    val tlsMode: TlsRenderMode,
    val certificatePath: String? = null,
    val certificateKeyPath: String? = null,
    val upstreamMode: UpstreamMode = UpstreamMode.EXPLICIT_PORT,
    val certMode: CertMode = CertMode.AUTO_RESOLVE,
    val gateEnabled: Boolean = true,
    val bypassPaths: List<String> = listOf("/api/gate/", "/api/paystack/", "/api/mpesa/"),
    val serviceId: UUID? = null,
    val siteId: UUID? = null,
    val upstreamKind: String? = null,
    val customNginxLocations: List<NginxCustomLocation> = emptyList()
)

val DEFAULT_GATEKEEPER_BYPASS_PATHS = listOf("/api/gate/", "/api/paystack/", "/api/mpesa/")

enum class TlsRenderMode { HTTP_ONLY, HTTPS, HTTPS_HTTP2 }
