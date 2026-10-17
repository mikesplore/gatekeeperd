package com.gatekeeper.nginx

import java.io.File

/** Pure renderer for Gatekeeper-managed nginx site configuration. */
class NginxConfigRenderer(
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = "/etc/letsencrypt/live",
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams"
) {

    fun render(site: NginxSiteRenderModel): String = render(
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

    fun render(
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
    ): String {
        requireValidHostname(domain)
        val effectiveSslCert = sslCertificatePath?.let { requireCertificatePath(it, sslCertPath) }
            ?: "$sslCertPath/$domain/fullchain.pem"
        val effectiveSslKey = sslCertificateKeyPath?.let { requireCertificatePath(it, sslCertPath) }
            ?: "$sslCertPath/$domain/privkey.pem"
        val effectiveUpstreamScheme = normalizeUpstreamScheme(appPort, upstreamScheme)
        val managedUpstream = upstreamKind == "DEPLOYMENT_MANAGED" && serviceId != null
        val upstreamName = serviceId?.let { DeploymentUpstreamIncludes(File(upstreamIncludeDirectory)).upstreamName(it) }
        val proxyTarget = if (managedUpstream) "$effectiveUpstreamScheme://$upstreamName" else "$effectiveUpstreamScheme://$upstreamHost:$appPort"

        return buildString {
            projectId?.let { appendLine("# gatekeeperd:project_id:$it") }
            serviceId?.let { appendLine("# gatekeeperd:service_id:$it") }
            siteId?.let { appendLine("# gatekeeperd:site_id:$it") }
            appendLine("server {")
            appendLine("    # gatekeeperd:block:server")
            if (sslEnabled) {
                appendLine("    listen 443 ssl;")
                appendLine("    listen [::]:443 ssl;")
                if (http2) appendLine("    http2 on;")
                appendLine()
                appendLine("    server_name $domain;")
                appendLine()
                appendLine("    ssl_certificate $effectiveSslCert;")
                appendLine("    ssl_certificate_key $effectiveSslKey;")
                appendLine()
            } else {
                appendLine("    listen 80;")
                appendLine("    listen [::]:80;")
                appendLine()
                appendLine("    server_name $domain;")
                appendLine()
            }

            if (gateEnabled) {
            appendLine("    # gatekeeperd:block:bypass")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 1. Gatekeeper Direct Bypass Route")
            appendLine("    # Payment callbacks, webhooks, and paywall APIs bypass auth_request")
            appendLine("    # -------------------------------------------------------------------------")
            bypassPaths.forEach { path ->
                require(path.startsWith("/") && path.endsWith("/") && !path.contains(';')) { "Invalid bypass path" }
                if (path == "/api/paystack/") appendLine("    # Payment provider callbacks must not be gated by the client project")
                appendLine("    location $path {")
                appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort$path;")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
                appendLine("    }")
                appendLine()
            }

            }

            if (managedUpstream) {
                appendLine("    # Local-only deployment cutover probe; the normal application route remains gated")
                appendLine("    location = /.well-known/gatekeeperd-cutover-probe {")
                appendLine("        allow 127.0.0.1;")
                appendLine("        allow ::1;")
                appendLine("        deny all;")
                appendLine("        auth_request off;")
                appendLine("        proxy_pass $proxyTarget;")
                appendLine("        add_header X-Gatekeeperd-Cutover-Probe stable always;")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
                appendLine("    }")
                appendLine()
            }

            customNginxLocations.forEach { location ->
                validateCustomLocation(location)
                appendLine("    # gatekeeperd:block:custom")
                appendLine("    location ${location.path} {")
                if (gateEnabled) {
                    appendLine("        auth_request /gatekeeper-auth-$slug;")
                    appendLine("        error_page 403 = @gatekeeper_paywall_$slug;")
                }
                appendLine("        proxy_pass $proxyTarget;")
                location.directives.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        appendLine("        ${line.trim()}")
                    }
                }
                appendLine("    }")
                appendLine()
            }

            appendLine("    # -------------------------------------------------------------------------")
            appendLine(if (gateEnabled) "    # 2. Main Protected Application Route" else "    # 2. Main Application Route")
            if (gateEnabled) appendLine("    # Evaluates gatekeeper auth_request on every incoming request")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # gatekeeperd:block:upstream")
            appendLine("    location / {")
                if (gateEnabled) {
                    appendLine("        auth_request /gatekeeper-auth-$slug;")
                    appendLine("        error_page 403 = @gatekeeper_paywall_$slug;")
                }
                appendLine()
                appendLine("        proxy_pass $proxyTarget;")
                appendLine("        proxy_http_version 1.1;")
                appendLine()
                appendLine("        proxy_set_header Upgrade \$http_upgrade;")
                appendLine("        proxy_set_header Connection \"upgrade\";")
                appendLine("        proxy_set_header Host \$host;")
                appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
                appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
                appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            appendLine()

            if (gateEnabled) {
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 3. Isolated Gatekeeper Subrequest (Browser-Header Sanitizer)")
            appendLine("    # Strips all browser headers, CORS metadata, and POST bodies")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # gatekeeperd:block:auth_request")
            appendLine("    location = /gatekeeper-auth-$slug {")
                appendLine("        internal;")
                appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort/api/gate/auth?project=$slug&domain=$domain;")
                appendLine()
                appendLine("        # Always force subrequest method to GET")
                appendLine("        proxy_method GET;")
                appendLine()
                appendLine("        # Disable body forwarding")
                appendLine("        proxy_pass_request_body off;")
                appendLine()
                appendLine("        # Strip all body and content headers")
                appendLine("        proxy_set_header Content-Length \"\";")
                appendLine("        proxy_set_header Content-Type \"\";")
                appendLine("        proxy_set_header Transfer-Encoding \"\";")
                appendLine()
                appendLine("        # Strip browser CORS and metadata headers that trigger 0ms 403s in Ktor")
                appendLine("        proxy_set_header Authorization \"\";")
                appendLine("        proxy_set_header Cookie \"\";")
                appendLine("        proxy_set_header Origin \"\";")
                appendLine("        proxy_set_header Referer \"\";")
                appendLine("        proxy_set_header User-Agent \"Nginx-Auth-Check\";")
                appendLine("        proxy_set_header Accept \"\";")
                appendLine("        proxy_set_header Accept-Encoding \"\";")
                appendLine("        proxy_set_header Accept-Language \"\";")
                appendLine("        proxy_set_header Sec-Fetch-Dest \"\";")
                appendLine("        proxy_set_header Sec-Fetch-Mode \"\";")
                appendLine("        proxy_set_header Sec-Fetch-Site \"\";")
                appendLine("        proxy_set_header Sec-Ch-Ua \"\";")
                appendLine()
                appendLine("        # Point Host header strictly to 127.0.0.1")
                appendLine("        proxy_set_header Host 127.0.0.1;")
                appendLine()
                appendLine("        proxy_http_version 1.1;")
                appendLine("        proxy_set_header Connection \"\";")
            appendLine("    }")
            appendLine()
            }

            if (gateEnabled) {
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # 4. Paywall Fallback Location")
            appendLine("    # -------------------------------------------------------------------------")
            appendLine("    # gatekeeperd:block:paywall")
            appendLine("    location @gatekeeper_paywall_$slug {")
            appendLine("        rewrite ^ /api/gate/paywall?project=$slug&domain=$domain break;")
            appendLine("        proxy_pass http://127.0.0.1:$gatekeeperPort;")
            appendLine("        proxy_set_header Host \$host;")
            appendLine("        proxy_set_header X-Real-IP \$remote_addr;")
            appendLine("        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;")
            appendLine("        proxy_set_header X-Forwarded-Proto \$scheme;")
            appendLine("    }")
            }
            appendLine("}")
        }
    }

    private fun normalizeUpstreamScheme(appPort: Int, upstreamScheme: String?): String {
        val normalized = upstreamScheme?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (normalized == null) return if (appPort == 443) "https" else "http"
        require(normalized == "http" || normalized == "https") {
            "upstreamScheme must be either http or https"
        }
        return normalized
    }

    private fun validateCustomLocation(location: NginxCustomLocation) {
        require(location.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid custom location ID" }
        require(location.path.matches(Regex("/(?:[A-Za-z0-9._~-]+/?)+")) &&
            location.path.split('/').none { it == "." || it == ".." }) {
            "Location path must be a literal URI prefix such as /socket.io/"
        }
        require(!location.directives.contains('{') && !location.directives.contains('}')) {
            "Nested nginx blocks are not supported in custom locations"
        }
        val proxyPasses = location.directives.lineSequence()
            .filter { it.trimStart().matches(Regex("^proxy_pass(?:\\s|$).*")) }
        require(proxyPasses.none()) {
            "The application upstream is added automatically; custom proxy_pass directives are not allowed"
        }
    }

}
