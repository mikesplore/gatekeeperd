package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.DockerService
import com.gatekeeper.nginx.CertificateInstallRequest
import com.gatekeeper.nginx.CertificateListResponse
import com.gatekeeper.nginx.CertificateResponse
import com.gatekeeper.nginx.CertificateRenewalResponse
import com.gatekeeper.nginx.InstalledCertificateInfo
import com.gatekeeper.nginx.NginxEnableRequest
import com.gatekeeper.nginx.NginxStatusResponse
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.NginxBlockUpdateRequest
import com.gatekeeper.nginx.NginxSiteRenderModel
import com.gatekeeper.nginx.DeploymentUpstreamResolver
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.nginx.TlsRenderMode
import com.gatekeeper.nginx.ResolvedCertificate
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

private fun activeDefaultRuntime(projectId: java.util.UUID) = runCatching {
    val serviceId = SiteRepository.findDefaultServiceId(projectId)
    DeploymentApplicationService.activeDeploymentRuntime(serviceId, "production")
}.getOrNull()

private fun resolvedDefaultRuntimeTarget(projectId: java.util.UUID): DeploymentUpstreamResolver.Target? {
    val runtime = activeDefaultRuntime(projectId) ?: return null
    val containerPort = runtime.containerPort?.takeIf { it in 1..65535 }
        ?: runtime.publishedPorts.keys.singleOrNull()?.takeIf { it in 1..65535 }
        ?: return null
    val hostPort = runtime.publishedPorts[containerPort]?.takeIf { it in 1..65535 } ?: return null
    return DeploymentUpstreamResolver.Target("127.0.0.1", hostPort, runtime.containerName)
}

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
    val nginxEnabled: Boolean,
    val resolvedUpstreamHost: String? = null,
    val configuredPort: Int? = null,
    val runtimeHealth: String? = null,
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
    nginxService: NginxService
): NginxSiteRenderModel {
    val (upstreamHost, port) = when (site.upstreamMode) {
        UpstreamMode.EXPLICIT_PORT -> site.upstreamHost to
            (site.upstreamExplicitPort ?: error("Site $slug has no explicit upstream port"))
        UpstreamMode.DOCKER_DISCOVERY -> {
            val target = site.serviceId?.let { DeploymentUpstreamResolver.resolve(it, "production") }
                ?: error("Could not resolve the active deployment upstream for Docker site $slug")
            val containerName = target.containerName
                ?: error("Active deployment runtime has no container name for Docker site $slug")
            target.host to target.port
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
    activeRuntimeName: String?,
    request: NginxEnableRequest,
    nginxService: NginxService,
    dockerService: DockerService?
): NginxPlanResult {
    val explicitPort = request.port
    if (explicitPort != null && explicitPort !in 1..65535) {
        return NginxPlanResult.Err(HttpStatusCode.BadRequest, "invalid_request", "port must be between 1 and 65535")
    }

    val configuredContainerName = activeRuntimeName?.takeIf(String::isNotBlank)
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

    val appPort = explicitPort ?: dockerPublishedHostPorts?.singleOrNull()

    if (appPort == null) {
        if (dockerPublishedHostPorts != null) {
            return NginxPlanResult.Err(
                HttpStatusCode.BadRequest,
                "missing_port",
                when {
                    dockerPublishedHostPorts.isEmpty() ->
                        "Could not infer the active deployment upstream port. Provide 'port' in the request body or configure a deployment runtime."
                    else ->
                        "Multiple published host ports detected. Provide 'port' in the request body or configure the deployment container port."
                },
                buildJsonObject {
                    put("resolvedUpstream", JsonPrimitive(configuredContainerName.orEmpty()))
                    putJsonArray("publishedHostPorts") { dockerPublishedHostPorts.sorted().forEach { add(JsonPrimitive(it)) } }
                }
            )
        }

        return NginxPlanResult.Err(
            HttpStatusCode.BadRequest,
            "missing_port",
                "Provide a valid port in the request body, or configure an active deployment runtime for port inference."
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
                "Port $appPort is not reachable on 127.0.0.1 for project $slug. Ensure the active deployment upstream is listening on the host."
            )
        }
    }

    val upstreamScheme = request.upstreamScheme?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "http"
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
    val requireSsl = request.requireSsl ?: true

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
    val nginxService = NginxService.configured()
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

                val nginxEnabled = nginxService.listConfigArtifacts().any { it.filename == slug && it.available && it.enabled }

                val site = SiteRepository.findByProjectId(project.id)
                val activeRuntime = activeDefaultRuntime(project.id)
                val resolvedTarget = site?.serviceId?.let { DeploymentUpstreamResolver.resolve(it, "production") }
                    ?: resolvedDefaultRuntimeTarget(project.id)
                val configuredPort = resolvedTarget?.port

                val dockerHealth = runCatching {
                    if (dockerService == null || activeRuntime?.containerName == null) null
                    else dockerService.containerHealth(activeRuntime.containerName)
                }.getOrNull()

                val installedCerts = nginxService.listInstalledCertificates().map { it.certificateDomain }.sorted()
                val domain = site?.domain ?: project.domain
                val resolvedCert = nginxService.resolveCertificateForDomain(domain)

                call.respond(
                    NginxWizardContextResponse(
                        slug = slug,
                        domain = domain,
                        nginxEnabled = nginxEnabled,
                        resolvedUpstreamHost = resolvedTarget?.host,
                        configuredPort = configuredPort,
                        runtimeHealth = dockerHealth,
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
                    activeRuntimeName = activeDefaultRuntime(project.id)?.containerName,
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

                val enabled = nginxService.listConfigArtifacts().any { it.filename == slug && it.available && it.enabled }

                val resolvedCert = nginxService.resolveCertificateForDomain(project.domain)
                val expiry = nginxService.certificateExpiry(resolvedCert?.certificateDomain ?: project.domain)

                call.respond(
                    NginxStatusResponse(
                        enabled = enabled,
                        configPath = "$sitesAvailablePath/$slug",
                        enabledPath = "$sitesEnabledPath/$slug",
                        port = SiteRepository.findByProjectId(project.id)?.serviceId?.let { DeploymentUpstreamResolver.resolve(it, "production")?.port },
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
                } catch (_: Exception) {
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
                    runCatching { renderModelFromSite(slug, site, nginxService) }
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
                        activeRuntimeName = activeDefaultRuntime(project.id)?.containerName,
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
                        projectId = project.id,
                        serviceId = SiteRepository.findDefaultServiceId(project.id),
                        domain = plan.domain,
                        appPort = plan.appPort,
                        upstreamScheme = plan.upstreamScheme,
                        tlsMode = if (plan.sslEnabled) TlsRenderMode.HTTPS else TlsRenderMode.HTTP_ONLY,
                        certificatePath = plan.resolvedCertificate?.certificatePath.takeIf { body.sslCertificatePath != null },
                        certificateKeyPath = plan.resolvedCertificate?.privateKeyPath.takeIf { body.sslCertificatePath != null },
                        upstreamMode = UpstreamMode.EXPLICIT_PORT,
                        certMode = if (body.sslCertificatePath != null) CertMode.EXPLICIT_PATH else CertMode.AUTO_RESOLVE
                    )
                    nginxService.generateNginxConfig(
                        slug = slug,
                        domain = plan.domain,
                        appPort = responseAppPort,
                        upstreamScheme = plan.upstreamScheme,
                        sslEnabled = responseSslEnabled,
                        sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                        sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath,
                        projectId = project.id,
                        serviceId = SiteRepository.findDefaultServiceId(project.id)
                    )
                }

                val enabledSlug = siteToPersist?.slug ?: slug
                val enabled = nginxService.enableProject(enabledSlug, config)
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
                    nginxService.disableProject(enabledSlug)
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx")
                    return@post
                }

                siteToPersist?.let { model ->
                    runCatching { SiteRepository.create(project.id, model) }
                        .onFailure { error ->
                            logger.error("Nginx site enabled but database registration failed for project $slug", error)
                            nginxService.removeProject(enabledSlug)
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
                    val expiry = nginxService.certificateExpiry(it.certificateDomain)
                    val expiresAt = expiry?.first?.let { value -> java.time.OffsetDateTime.parse(value).toLocalDateTime() }
                    val renewalStatus = when {
                        expiry == null -> "unknown"
                        java.time.OffsetDateTime.parse(expiry.first).toInstant().isBefore(java.time.Instant.now()) -> "expired"
                        else -> "active"
                    }
                    CertificateRepository.syncInventory(it.certificateDomain, expiresAt, renewalStatus)
                    InstalledCertificateInfo(
                        certificateDomain = it.certificateDomain,
                        certificatePath = it.certificatePath,
                        privateKeyPath = it.privateKeyPath,
                        certificateExpiresAt = expiry?.first,
                        certificateDaysRemaining = expiry?.second,
                        renewalStatus = renewalStatus
                    )
                }

                call.respond(CertificateListResponse(certificates = certificates))
            }

            post("/api/admin/nginx/certificate/install") {
                val body = try {
                    call.receive<CertificateInstallRequest>()
                } catch (_: Exception) {
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

            post("/api/admin/nginx/certificate/renew/{domain}") {
                val domain = call.parameters["domain"]
                if (domain == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
                    return@post
                }
                val validatedDomain = runCatching { requireValidHostname(domain) }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_domain", it.message ?: "Invalid domain")
                    return@post
                }
                if (!nginxService.isCertificateInstalled(validatedDomain)) {
                    call.respondError(HttpStatusCode.NotFound, "certificate_not_found", "No installed certificate found for '$validatedDomain'")
                    return@post
                }
                if (!nginxService.isCertbotAvailable()) {
                    call.respondError(HttpStatusCode.BadRequest, "certbot_unavailable", "Certbot is not installed on this system")
                    return@post
                }

                val beforeExpiry = nginxService.certificateExpiry(validatedDomain)
                if (!nginxService.renewCertificate(validatedDomain)) {
                    val expiresAt = beforeExpiry?.first?.let { java.time.OffsetDateTime.parse(it).toLocalDateTime() }
                    val status = when {
                        beforeExpiry == null -> "unknown"
                        beforeExpiry.second < 0 -> "expired"
                        else -> "active"
                    }
                    CertificateRepository.recordRenewalResult(validatedDomain, expiresAt, status, "Certbot renewal failed")
                    call.respondError(HttpStatusCode.InternalServerError, "certificate_renewal_failed", "Certificate renewal failed. Check Certbot and nginx service logs.")
                    return@post
                }

                val afterExpiry = nginxService.certificateExpiry(validatedDomain)
                val expiryInstant = afterExpiry?.first?.let(java.time.OffsetDateTime::parse)
                val renewalStatus = when {
                    expiryInstant == null -> "unknown"
                    expiryInstant.toInstant().isBefore(java.time.Instant.now()) -> "expired"
                    else -> "active"
                }
                CertificateRepository.recordRenewalResult(
                    validatedDomain,
                    expiryInstant?.toLocalDateTime(),
                    renewalStatus
                )
                val renewed = beforeExpiry?.first != afterExpiry?.first
                call.respond(
                    CertificateRenewalResponse(
                        domain = validatedDomain,
                        renewed = renewed,
                        certificateExpiresAt = afterExpiry?.first,
                        certificateDaysRemaining = afterExpiry?.second,
                        message = if (renewed) "Certificate renewed successfully" else "Certificate is not due for renewal yet"
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
