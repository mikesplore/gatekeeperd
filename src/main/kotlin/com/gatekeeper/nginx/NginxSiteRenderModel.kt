package com.gatekeeper.nginx

data class NginxSiteRenderModel(
    val slug: String,
    val domain: String,
    val upstreamHost: String = "127.0.0.1",
    val appPort: Int,
    val upstreamScheme: String,
    val tlsMode: TlsRenderMode,
    val certificatePath: String? = null,
    val certificateKeyPath: String? = null,
    val upstreamMode: com.gatekeeper.db.tables.UpstreamMode = com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT,
    val upstreamContainerName: String? = null,
    val certMode: com.gatekeeper.db.tables.CertMode = com.gatekeeper.db.tables.CertMode.AUTO_RESOLVE
)

enum class TlsRenderMode { HTTP_ONLY, HTTPS, HTTPS_HTTP2 }
