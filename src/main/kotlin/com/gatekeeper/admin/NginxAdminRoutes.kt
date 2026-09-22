package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.DockerService
import com.gatekeeper.nginx.CertificateInstallRequest
import com.gatekeeper.nginx.CertificateListResponse
import com.gatekeeper.nginx.CertificateResponse
import com.gatekeeper.nginx.InstalledCertificateInfo
import com.gatekeeper.nginx.NginxEnableRequest
import com.gatekeeper.nginx.NginxStatusResponse
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.NginxConfigInspection
import com.gatekeeper.nginx.NginxTestResult
import com.gatekeeper.nginx.NginxBlockUpdateRequest
import com.gatekeeper.nginx.NginxSiteRenderModel
import com.gatekeeper.nginx.TlsRenderMode
import com.gatekeeper.nginx.ResolvedCertificate
import com.gatekeeper.nginx.extractConfiguredContainerName
import com.gatekeeper.nginx.extractConfiguredPort
import com.gatekeeper.nginx.parsePublishedHostPorts
import com.gatekeeper.nginx.requireCertificatePath
import com.gatekeeper.nginx.requireValidEmail
import com.gatekeeper.nginx.requireValidHostname
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.repositories.CertificateRepository
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.admin.NginxAdminRoutes")

@Serializable
data class NginxEnableResponse(
    val success: Boolean,
    val message: String,
    val config: String? = null,
    val appPort: Int? = null,
    val sslEnabled: Boolean? = null,
    val certificateDomain: String? = null
)

@Serializable
data class NginxDisableResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class NginxWizardContextResponse(
    val slug: String,
    val domain: String,
    val containerName: String,
    val nginxEnabled: Boolean,
    val configuredContainerName: String? = null,
    val configuredPort: Int? = null,
    val dockerContainerHealth: String? = null,
    val dockerPublishedHostPorts: List<Int>? = null,
    val installedCertificates: List<String> = emptyList(),
    val resolvedCertificateDomain: String? = null
)

private data class NginxEnablePlan(
    val slug: String,
    val domain: String,
    val appPort: Int,
    val upstreamScheme: String,
    val resolvedCertificate: ResolvedCertificate?
) {
    val sslEnabled: Boolean get() = resolvedCertificate != null
}

private sealed interface NginxPlanResult {
    data class Ok(val plan: NginxEnablePlan) : NginxPlanResult
    data class Err(
        val status: HttpStatusCode,
        val code: String,
        val message: String,
        val data: JsonObject? = null
    ) : NginxPlanResult
}

private fun renderModelFromSite(
    slug: String,
    site: SiteRepository.SiteRecord,
    nginxService: NginxService,
    dockerService: DockerService?
): NginxSiteRenderModel {
    val port = when (site.upstreamMode) {
        UpstreamMode.EXPLICIT_PORT -> site.upstreamExplicitPort
            ?: error("Site $slug has no explicit upstream port")
        UpstreamMode.DOCKER_DISCOVERY -> {
            val container = site.upstreamContainerName?.let { dockerService?.getContainer(it) }
            parsePublishedHostPorts(container?.ports.orEmpty()).singleOrNull()
                ?: error("Could not resolve one Docker upstream port for site $slug")
        }
    }
    val certificate = when (site.certMode) {
        CertMode.AUTO_RESOLVE -> nginxService.resolveCertificateForDomain(site.domain)
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
        domain = site.domain,
        upstreamHost = site.upstreamHost,
        appPort = port,
        upstreamScheme = if (tls == TlsRenderMode.HTTP_ONLY) "http" else "https",
        tlsMode = tls,
        certificatePath = certificate?.certificatePath,
        certificateKeyPath = certificate?.privateKeyPath,
        upstreamMode = site.upstreamMode,
        upstreamContainerName = site.upstreamContainerName,
        certMode = site.certMode,
        gateEnabled = site.gateEnabled,
        bypassPaths = site.bypassPaths
    )
}

internal fun hasRenderAffectingNginxParameters(body: NginxEnableRequest): Boolean =
    body.port != null || body.domain != null || body.upstreamScheme != null ||
        body.certificateDomain != null || body.sslCertificatePath != null ||
        body.sslCertificateKeyPath != null || body.requireSsl != null

private fun computeNginxEnablePlan(
    slug: String,
    projectDomain: String,
    projectContainerName: String,
    request: NginxEnableRequest,
    nginxService: NginxService,
    dockerService: DockerService?
): NginxPlanResult {
    val explicitPort = request.port
    if (explicitPort != null && explicitPort !in 1..65535) {
        return NginxPlanResult.Err(HttpStatusCode.BadRequest, "invalid_request", "port must be between 1 and 65535")
    }

    val configuredContainerName = extractConfiguredContainerName(projectContainerName)
    val dockerPublishedHostPorts = run {
        if (dockerService == null || configuredContainerName == null) return@run null
        val health = dockerService.containerHealth(configuredContainerName)
        if (health != "running") {
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "port_not_active",
                "Container '$configuredContainerName' is not running for project $slug (state: $health). Start it and try again."
            )
        }
        val info = dockerService.getContainer(configuredContainerName)
        parsePublishedHostPorts(info?.ports.orEmpty())
    }

    val appPort = explicitPort
        ?: extractConfiguredPort(projectContainerName)
        ?: dockerPublishedHostPorts?.singleOrNull()

    if (appPort == null) {
        if (dockerPublishedHostPorts != null) {
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "missing_port",
                when {
                    dockerPublishedHostPorts.isEmpty() ->
                        "Could not infer upstream port from Docker. Provide 'port' in the request body or encode containerName as name:port."
                    else ->
                        "Multiple published host ports detected. Provide 'port' in the request body or encode containerName as name:port."
                },
                buildJsonObject {
                    put("containerName", JsonPrimitive(configuredContainerName ?: projectContainerName))
                    putJsonArray("publishedHostPorts") { dockerPublishedHostPorts.sorted().forEach { add(JsonPrimitive(it)) } }
                }
            )
        }

        return NginxPlanResult.Err(
            HttpStatusCode.BadRequest,
            "missing_port",
            "Provide a valid port in the request body, encode containerName as name:port, or ensure Docker is available for port inference."
        )
    }

    // Prefer Docker-based validation: works even when gatekeeperd runs in a container (127.0.0.1 differs).
    if (dockerPublishedHostPorts != null) {
        if (dockerPublishedHostPorts.isNotEmpty() && appPort !in dockerPublishedHostPorts) {
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "port_not_active",
                "Container '$configuredContainerName' is running but does not publish host port $appPort (published: ${dockerPublishedHostPorts.sorted().joinToString(", ")}). Nginx proxies to 127.0.0.1:$appPort."
            )
        }
    } else {
        if (!nginxService.isPortActive(appPort)) {
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "port_not_active",
                "Port $appPort is not reachable on 127.0.0.1 for project $slug. Ensure the upstream is listening on the host, or set containerName to a Docker container so Docker-based validation can be used."
            )
        }
    }

    val upstreamScheme = request.upstreamScheme?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: if (appPort == 443) "https" else "http"
    if (upstreamScheme !in listOf("http", "https")) {
        return NginxPlanResult.Err(HttpStatusCode.BadRequest, "invalid_request", "upstreamScheme must be 'http' or 'https'")
    }

    val domain = try { requireValidHostname(projectDomain) } catch (e: IllegalArgumentException) {
        return NginxPlanResult.Err(HttpStatusCode.BadRequest, "invalid_domain", e.message ?: "Project domain is invalid")
    }
    if (domain.isBlank()) {
        return NginxPlanResult.Err(HttpStatusCode.BadRequest, "no_domain", "Project has no domain configured")
    }

    val explicitCertPath = request.sslCertificatePath?.trim()?.takeIf { it.isNotBlank() }
    val explicitKeyPath = request.sslCertificateKeyPath?.trim()?.takeIf { it.isNotBlank() }
    val requireSsl = request.requireSsl ?: false

    if ((explicitCertPath == null) != (explicitKeyPath == null)) {
        return NginxPlanResult.Err(
            HttpStatusCode.BadRequest,
            "invalid_request",
            "Provide both sslCertificatePath and sslCertificateKeyPath (or neither)."
        )
    }

    val resolvedCertificate = when {
        explicitCertPath != null && explicitKeyPath != null -> {
            val certPath = try { requireCertificatePath(explicitCertPath, AppConfig.nginxSslCertPath) } catch (e: IllegalArgumentException) {
                return NginxPlanResult.Err(HttpStatusCode.BadRequest, "invalid_certificate_path", e.message ?: "Invalid SSL certificate path")
            }
            val keyPath = try { requireCertificatePath(explicitKeyPath, AppConfig.nginxSslCertPath) } catch (e: IllegalArgumentException) {
                return NginxPlanResult.Err(HttpStatusCode.BadRequest, "invalid_certificate_path", e.message ?: "Invalid SSL certificate path")
            }
            val certFile = java.io.File(certPath)
            val keyFile = java.io.File(keyPath)
            if (!certFile.exists() || !keyFile.exists()) {
                return NginxPlanResult.Err(
                    HttpStatusCode.BadRequest,
                    "certificate_not_found",
                    "SSL certificate files are not accessible at the specified paths. " +
                        "certPath='${certFile.absolutePath}' (exists=${certFile.exists()}, readable=${certFile.canRead()}), " +
                        "keyPath='${keyFile.absolutePath}' (exists=${keyFile.exists()}, readable=${keyFile.canRead()})."
                )
            }

            ResolvedCertificate(
                certificateDomain = "custom",
                certificatePath = certFile.absolutePath,
                privateKeyPath = keyFile.absolutePath
            )
        }

        else -> nginxService.resolveCertificateForDomain(
            domain = domain,
            requestedCertificateDomain = request.certificateDomain
        )
    }

    if (request.certificateDomain != null && resolvedCertificate == null) {
        val installed = nginxService.listInstalledCertificates().map { it.certificateDomain }
        return NginxPlanResult.Err(
            HttpStatusCode.BadRequest,
            "certificate_not_found",
            "No installed certificate found for '${request.certificateDomain}'.",
            buildJsonObject {
                put("requestedCertificateDomain", JsonPrimitive(request.certificateDomain))
                putJsonArray("installedCertificates") { installed.forEach { add(JsonPrimitive(it)) } }
            }
        )
    }

    if (requireSsl && resolvedCertificate == null) {
        val installed = nginxService.listInstalledCertificates().map { it.certificateDomain }
        return NginxPlanResult.Err(
            HttpStatusCode.BadRequest,
            "certificate_not_found",
            "No SSL certificate found for '$domain' (or its parent domains).",
            buildJsonObject {
                put("domain", JsonPrimitive(domain))
                putJsonArray("installedCertificates") { installed.forEach { add(JsonPrimitive(it)) } }
            }
        )
    }

    return NginxPlanResult.Ok(
        NginxEnablePlan(
            slug = slug,
            domain = domain,
            appPort = appPort,
            upstreamScheme = upstreamScheme,
            resolvedCertificate = resolvedCertificate
        )
    )
}

fun Application.configureNginxAdminRoutes() {
    val nginxService = NginxService()
    val dockerService: DockerService? = try {
        DockerService(AppConfig.dockerSocket)
    } catch (e: Exception) {
        logger.warn("Docker not available for nginx upstream validation (non-fatal): ${e.message}")
        null
    }

    routing {
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { dockerService?.close() }
        })

        authenticate("auth-jwt") {

            get("/api/admin/nginx/wizard/context/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }

                val sitesAvailablePath = AppConfig.nginxSitesAvailablePath
                val sitesEnabledPath = AppConfig.nginxSitesEnabledPath
                val nginxEnabled = run {
                    val available = java.io.File("$sitesAvailablePath/$slug").exists()
                    val enabledLink = java.io.File("$sitesEnabledPath/$slug").exists()
                    available && enabledLink
                }

                val configuredContainerName = extractConfiguredContainerName(project.containerName)
                val configuredPort = extractConfiguredPort(project.containerName)

                val dockerHealth = runCatching {
                    if (dockerService == null || configuredContainerName == null) null
                    else dockerService.containerHealth(configuredContainerName)
                }.getOrNull()

                val publishedPorts = runCatching {
                    if (dockerService == null || configuredContainerName == null) null
                    else parsePublishedHostPorts(dockerService.getContainer(configuredContainerName)?.ports.orEmpty()).sorted()
                }.getOrNull()

                val installedCerts = nginxService.listInstalledCertificates().map { it.certificateDomain }.sorted()
                val resolvedCert = nginxService.resolveCertificateForDomain(project.domain)
                val expiry = nginxService.certificateExpiry(resolvedCert?.certificateDomain ?: project.domain)

                call.respond(
                    NginxWizardContextResponse(
                        slug = slug,
                        domain = project.domain,
                        containerName = project.containerName,
                        nginxEnabled = nginxEnabled,
                        configuredContainerName = configuredContainerName,
                        configuredPort = configuredPort,
                        dockerContainerHealth = dockerHealth,
                        dockerPublishedHostPorts = publishedPorts,
                        installedCertificates = installedCerts,
                        resolvedCertificateDomain = resolvedCert?.certificateDomain
                    )
                )
            }

            post("/api/admin/nginx/wizard/validate/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                val body = try {
                    call.receive<NginxEnableRequest>()
                } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }

                when (val result = computeNginxEnablePlan(
                    slug = slug,
                    projectDomain = project.domain,
                    projectContainerName = project.containerName,
                    request = body,
                    nginxService = nginxService,
                    dockerService = dockerService
                )) {
                    is NginxPlanResult.Err -> {
                        if (result.data != null) {
                            call.respondErrorWithData(result.status, result.code, result.message, result.data)
                        } else {
                            call.respondError(result.status, result.code, result.message)
                        }
                        return@post
                    }
                    is NginxPlanResult.Ok -> {
                        val plan = result.plan
                        val config = nginxService.generateNginxConfig(
                            slug = slug,
                            domain = plan.domain,
                            appPort = plan.appPort,
                            upstreamScheme = plan.upstreamScheme,
                            sslEnabled = plan.sslEnabled,
                            sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                            sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath
                        )

                        call.respond(
                            NginxEnableResponse(
                                success = true,
                                message = "Validated successfully (no changes applied)",
                                config = config,
                                appPort = plan.appPort,
                                sslEnabled = plan.sslEnabled,
                                certificateDomain = plan.resolvedCertificate?.certificateDomain
                            )
                        )
                    }
                }
            }

            get("/api/admin/nginx/status/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }

                val sitesAvailablePath = AppConfig.nginxSitesAvailablePath
                val sitesEnabledPath = AppConfig.nginxSitesEnabledPath
                val enabled = run {
                    val available = java.io.File("$sitesAvailablePath/$slug").exists()
                    val enabledLink = java.io.File("$sitesEnabledPath/$slug").exists()
                    available && enabledLink
                }

                val resolvedCert = nginxService.resolveCertificateForDomain(project.domain)
                val expiry = nginxService.certificateExpiry(resolvedCert?.certificateDomain ?: project.domain)

                call.respond(
                    NginxStatusResponse(
                        enabled = enabled,
                        configPath = "$sitesAvailablePath/$slug",
                        enabledPath = "$sitesEnabledPath/$slug",
                        port = extractConfiguredPort(project.containerName),
                        sslEnabled = resolvedCert != null,
                        certificateDomain = resolvedCert?.certificateDomain,
                        domain = project.domain,
                        certificateExpiresAt = expiry?.first,
                        certificateDaysRemaining = expiry?.second
                    )
                )
            }

            get("/api/admin/nginx/config/{slug}") {
                val slug = call.parameters["slug"]
                if (slug.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }
                val inspection = runCatching { nginxService.inspectSite(slug) }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_slug", it.message ?: "Invalid site name")
                    return@get
                }
                call.respond(inspection)
            }

            get("/api/admin/nginx/diagnostics") {
                call.respond(nginxService.testNginxConfigDetailed())
            }

            post("/api/admin/nginx/test") {
                call.respond(nginxService.testNginxConfigDetailed())
            }

            post("/api/admin/nginx/config/{slug}/blocks/{index}/preview") {
                val slug = call.parameters["slug"]
                val index = call.parameters["index"]?.toIntOrNull()
                if (slug.isNullOrBlank() || index == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A valid slug and block index are required")
                    return@post
                }
                val body = runCatching { call.receive<NginxBlockUpdateRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid block update body"); return@post }
                val preview = runCatching { nginxService.previewBlockUpdate(slug, index, body.content) }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_block", it.message ?: "Unable to preview block"); return@post }
                call.respond(mapOf("slug" to slug, "blockIndex" to index, "config" to preview))
            }

            post("/api/admin/nginx/config/{slug}/blocks/{index}/apply") {
                val slug = call.parameters["slug"]
                val index = call.parameters["index"]?.toIntOrNull()
                if (slug.isNullOrBlank() || index == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A valid slug and block index are required")
                    return@post
                }
                val body = runCatching { call.receive<NginxBlockUpdateRequest>() }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid block update body"); return@post }
                val result = runCatching { nginxService.applyBlockUpdate(slug, index, body.content) }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_block", it.message ?: "Unable to apply block"); return@post }
                if (!result.success) call.respond(HttpStatusCode.UnprocessableEntity, result) else call.respond(result)
                if (result.success) ProjectRepository.findBySlug(slug)?.let { project ->
                    AuditRepository.write(project.id, "nginx_block_updated", "admin", "Applied Nginx block $index for $slug")
                }
            }

            get("/api/admin/nginx/config/{slug}/versions") {
                val slug = call.parameters["slug"]
                if (slug.isNullOrBlank()) { call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter"); return@get }
                val versions = runCatching { nginxService.listBackups(slug) }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_slug", it.message ?: "Invalid site name"); return@get }
                call.respond(versions)
            }

            post("/api/admin/nginx/config/{slug}/rollback/{backup}") {
                val slug = call.parameters["slug"]
                val backup = call.parameters["backup"]
                if (slug.isNullOrBlank() || backup.isNullOrBlank()) { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Slug and backup name are required"); return@post }
                val result = runCatching { nginxService.rollback(slug, backup) }.getOrElse { call.respondError(HttpStatusCode.BadRequest, "rollback_failed", it.message ?: "Unable to roll back configuration"); return@post }
                if (!result.success) call.respond(HttpStatusCode.UnprocessableEntity, result) else call.respond(result)
                if (result.success) ProjectRepository.findBySlug(slug)?.let { project ->
                    AuditRepository.write(project.id, "nginx_rollback", "admin", "Rolled back Nginx configuration to $backup")
                }
            }

            post("/api/admin/nginx/enable/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                val body = try {
                    call.receive<NginxEnableRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }

                val site = SiteRepository.findByProjectSlug(slug)
                val renderAffectingRequest = hasRenderAffectingNginxParameters(body)
                if (site != null && renderAffectingRequest) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "site_parameters_not_allowed",
                        "This project is DB-driven; update its Site row instead of sending render-affecting enable parameters"
                    )
                    return@post
                }

                val responseAppPort: Int?
                val responseSslEnabled: Boolean
                val responseCertificateDomain: String?
                var siteToPersist: NginxSiteRenderModel? = null
                val config = if (site != null) {
                    runCatching { renderModelFromSite(slug, site, nginxService, dockerService) }
                        .getOrElse {
                            call.respondError(HttpStatusCode.UnprocessableEntity, "site_configuration_invalid", it.message ?: "Stored Site configuration is invalid")
                            return@post
                        }
                        .also {
                            responseAppPort = it.appPort
                            responseSslEnabled = it.tlsMode != TlsRenderMode.HTTP_ONLY
                            responseCertificateDomain = if (site.certMode == CertMode.AUTO_RESOLVE) nginxService.resolveCertificateForDomain(site.domain)?.certificateDomain else "explicit"
                        }
                        .let { nginxService.generateNginxConfig(it) }
                } else {
                    val plan = when (val result = computeNginxEnablePlan(
                    slug = slug,
                    projectDomain = project.domain,
                    projectContainerName = project.containerName,
                    request = body,
                    nginxService = nginxService,
                    dockerService = dockerService
                )) {
                    is NginxPlanResult.Err -> {
                        if (result.data != null) {
                            call.respondErrorWithData(result.status, result.code, result.message, result.data)
                        } else {
                            call.respondError(result.status, result.code, result.message)
                        }
                        return@post
                    }
                    is NginxPlanResult.Ok -> result.plan
                    }
                    responseAppPort = plan.appPort
                    responseSslEnabled = plan.sslEnabled
                    responseCertificateDomain = plan.resolvedCertificate?.certificateDomain
                    siteToPersist = NginxSiteRenderModel(
                        slug = slug,
                        domain = plan.domain,
                        appPort = plan.appPort,
                        upstreamScheme = plan.upstreamScheme,
                        tlsMode = if (plan.sslEnabled) TlsRenderMode.HTTPS else TlsRenderMode.HTTP_ONLY,
                        certificatePath = plan.resolvedCertificate?.certificatePath,
                        certificateKeyPath = plan.resolvedCertificate?.privateKeyPath,
                        upstreamMode = UpstreamMode.EXPLICIT_PORT,
                        upstreamContainerName = extractConfiguredContainerName(project.containerName),
                        certMode = if (body.sslCertificatePath != null) CertMode.EXPLICIT_PATH else CertMode.AUTO_RESOLVE
                    )
                    nginxService.generateNginxConfig(
                        slug = slug,
                        domain = plan.domain,
                        appPort = responseAppPort,
                        upstreamScheme = plan.upstreamScheme,
                        sslEnabled = responseSslEnabled,
                        sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                        sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath
                    )
                }

                val enabled = nginxService.enableProject(slug, config)
                if (!enabled) {
                    call.respondError(
                        HttpStatusCode.UnprocessableEntity,
                        "nginx_error",
                        "Nginx validation or activation failed; the previous site configuration was preserved"
                    )
                    return@post
                }

                val reloaded = nginxService.reloadNginx()
                if (!reloaded) {
                    nginxService.disableProject(slug)
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx")
                    return@post
                }

                siteToPersist?.let { model ->
                    runCatching { SiteRepository.create(project.id, model) }
                        .onFailure { error ->
                            logger.error("Nginx site enabled but database registration failed for project $slug", error)
                            nginxService.removeProject(slug)
                            nginxService.reloadNginx()
                            call.respondError(
                                HttpStatusCode.InternalServerError,
                                "site_registration_failed",
                                "Nginx site was enabled but could not be registered in the dashboard"
                            )
                            return@post
                        }
                }

                logger.info("Nginx enabled for project: $slug")
                call.respond(
                    NginxEnableResponse(
                        success = true,
                        message = "Nginx site enabled and reloaded successfully",
                        config = config,
                        appPort = responseAppPort,
                        sslEnabled = responseSslEnabled,
                        certificateDomain = responseCertificateDomain
                    )
                )
            }

            post("/api/admin/nginx/disable/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }

                val disabled = nginxService.disableProject(slug)
                if (!disabled) {
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to disable nginx site")
                    return@post
                }

                val reloaded = nginxService.reloadNginx()
                if (!reloaded) {
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx after disable")
                    return@post
                }

                logger.info("Nginx disabled for project: $slug")
                call.respond(
                    NginxDisableResponse(
                        success = true,
                        message = "Nginx site disabled and reloaded successfully"
                    )
                )
            }

            post("/api/admin/nginx/rollback/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val latest = runCatching { nginxService.listBackups(slug).firstOrNull()?.name }
                    .getOrElse { call.respondError(HttpStatusCode.BadRequest, "invalid_slug", it.message ?: "Invalid site name"); return@post }
                if (latest == null) {
                    call.respondError(HttpStatusCode.NotFound, "nginx_backup_not_found", "No nginx backup was found for this project")
                    return@post
                }
                val result = runCatching { nginxService.rollback(slug, latest) }
                    .getOrElse { call.respondError(HttpStatusCode.BadRequest, "rollback_failed", it.message ?: "Unable to roll back configuration"); return@post }
                if (!result.success) call.respond(HttpStatusCode.UnprocessableEntity, result) else call.respond(result)
                if (result.success) ProjectRepository.findBySlug(slug)?.let { project ->
                    AuditRepository.write(project.id, "nginx_rollback", "admin", "Rolled back Nginx configuration to $latest")
                }
            }

            post("/api/admin/nginx/remove/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }

                val removed = nginxService.removeProject(slug)
                if (!removed) {
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to remove nginx site")
                    return@post
                }

                val reloaded = nginxService.reloadNginx()
                if (!reloaded) {
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx after removal")
                    return@post
                }

                logger.info("Nginx site removed for project: $slug")
                call.respond(
                    NginxDisableResponse(
                        success = true,
                        message = "Nginx site removed and reloaded successfully"
                    )
                )
            }

            get("/api/admin/nginx/certificate/list") {
                val certificates = nginxService.listInstalledCertificates().map {
                    InstalledCertificateInfo(
                        certificateDomain = it.certificateDomain,
                        certificatePath = it.certificatePath,
                        privateKeyPath = it.privateKeyPath
                    )
                }

                call.respond(CertificateListResponse(certificates = certificates))
            }

            post("/api/admin/nginx/certificate/install") {
                val body = try {
                    call.receive<CertificateInstallRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                    return@post
                }

                val domain = body.domain.trim()
                val email = body.email.trim()

                val validatedDomain = runCatching { requireValidHostname(domain) }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Invalid domain")
                    return@post
                }
                val validatedEmail = runCatching { requireValidEmail(email) }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_email", it.message ?: "Invalid email")
                    return@post
                }

                if (!nginxService.isCertbotAvailable()) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "certbot_unavailable",
                        "Certbot is not installed on this system"
                    )
                    return@post
                }

                val installed = nginxService.installCertificate(validatedDomain, validatedEmail)
                if (!installed) {
                    call.respondError(HttpStatusCode.InternalServerError, "certificate_error", "Failed to install SSL certificate")
                    return@post
                }

                val certInstalled = nginxService.isCertificateInstalled(validatedDomain)
                val expiry = nginxService.certificateExpiry(validatedDomain)
                val certificate = CertificateRepository.upsert(
                    domain = validatedDomain,
                    issuedAt = java.time.LocalDateTime.now(),
                    expiresAt = expiry?.first?.let { java.time.OffsetDateTime.parse(it).toLocalDateTime() },
                    renewalStatus = if (expiry == null) "unknown" else "active"
                )
                SiteRepository.linkCertificateForDomain(validatedDomain, certificate.id)

                call.respond(
                    CertificateResponse(
                        domain = validatedDomain,
                        installed = certInstalled,
                        certificatePath = "${AppConfig.nginxSslCertPath}/$validatedDomain/fullchain.pem",
                        privateKeyPath = "${AppConfig.nginxSslCertPath}/$validatedDomain/privkey.pem"
                    )
                )
            }

            post("/api/admin/nginx/certificate/remove/{domain}") {
                val domain = call.parameters["domain"]
                if (domain == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
                    return@post
                }

                val validatedDomain = runCatching { requireValidHostname(domain) }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Invalid domain")
                    return@post
                }
                try {
                    CertificateRepository.requireRemovable(validatedDomain)
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.Conflict, "certificate_in_use", e.message ?: "Certificate is still referenced by active sites")
                    return@post
                }
                val removed = nginxService.removeCertificate(validatedDomain)
                if (!removed) {
                    call.respondError(HttpStatusCode.InternalServerError, "certificate_error", "Failed to remove SSL certificate")
                    return@post
                }
                CertificateRepository.deleteByDomain(validatedDomain)

                call.respond(
                    CertificateResponse(
                        domain = validatedDomain,
                        installed = false,
                        certificatePath = null,
                        privateKeyPath = null
                    )
                )
            }

            get("/api/admin/nginx/certificate/status/{domain}") {
                val domain = call.parameters["domain"]
                if (domain == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
                    return@get
                }

                val validatedDomain = runCatching { requireValidHostname(domain) }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Invalid domain")
                    return@get
                }
                val installed = nginxService.isCertificateInstalled(validatedDomain)

                call.respond(
                    CertificateResponse(
                        domain = validatedDomain,
                        installed = installed,
                        certificatePath = if (installed) "${AppConfig.nginxSslCertPath}/$validatedDomain/fullchain.pem" else null,
                        privateKeyPath = if (installed) "${AppConfig.nginxSslCertPath}/$validatedDomain/privkey.pem" else null
                    )
                )
            }
        }
    }
}
