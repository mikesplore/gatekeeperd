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
import com.gatekeeper.nginx.ResolvedCertificate
import com.gatekeeper.nginx.extractConfiguredContainerName
import com.gatekeeper.nginx.extractConfiguredPort
import com.gatekeeper.nginx.parsePublishedHostPorts
import com.gatekeeper.db.repositories.ProjectRepository
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

    val domain = projectDomain.trim()
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
            val certFile = java.io.File(explicitCertPath)
            val keyFile = java.io.File(explicitKeyPath)
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

                call.respond(
                    NginxStatusResponse(
                        enabled = enabled,
                        configPath = "$sitesAvailablePath/$slug",
                        enabledPath = "$sitesEnabledPath/$slug",
                        port = extractConfiguredPort(project.containerName),
                        sslEnabled = resolvedCert != null,
                        certificateDomain = resolvedCert?.certificateDomain,
                        domain = project.domain
                    )
                )
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

                val config = nginxService.generateNginxConfig(
                    slug = slug,
                    domain = plan.domain,
                    appPort = plan.appPort,
                    upstreamScheme = plan.upstreamScheme,
                    sslEnabled = plan.sslEnabled,
                    sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                    sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath
                )

                val enabled = nginxService.enableProject(slug, config)
                if (!enabled) {
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to enable nginx site")
                    return@post
                }

                val reloaded = nginxService.reloadNginx()
                if (!reloaded) {
                    nginxService.disableProject(slug)
                    call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx")
                    return@post
                }

                logger.info("Nginx enabled for project: $slug")
                call.respond(
                    NginxEnableResponse(
                        success = true,
                        message = "Nginx site enabled and reloaded successfully",
                        config = config,
                        appPort = plan.appPort,
                        sslEnabled = plan.sslEnabled,
                        certificateDomain = plan.resolvedCertificate?.certificateDomain
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

                if (domain.isBlank() || email.isBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "domain and email are required")
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

                val installed = nginxService.installCertificate(domain, email)
                if (!installed) {
                    call.respondError(HttpStatusCode.InternalServerError, "certificate_error", "Failed to install SSL certificate")
                    return@post
                }

                val certInstalled = nginxService.isCertificateInstalled(domain)

                call.respond(
                    CertificateResponse(
                        domain = domain,
                        installed = certInstalled,
                        certificatePath = "/etc/letsencrypt/live/$domain/fullchain.pem",
                        privateKeyPath = "/etc/letsencrypt/live/$domain/privkey.pem"
                    )
                )
            }

            post("/api/admin/nginx/certificate/remove/{domain}") {
                val domain = call.parameters["domain"]
                if (domain == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_domain", "Missing domain path parameter")
                    return@post
                }

                val removed = nginxService.removeCertificate(domain)
                if (!removed) {
                    call.respondError(HttpStatusCode.InternalServerError, "certificate_error", "Failed to remove SSL certificate")
                    return@post
                }

                call.respond(
                    CertificateResponse(
                        domain = domain,
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

                val installed = nginxService.isCertificateInstalled(domain)

                call.respond(
                    CertificateResponse(
                        domain = domain,
                        installed = installed,
                        certificatePath = if (installed) "/etc/letsencrypt/live/$domain/fullchain.pem" else null,
                        privateKeyPath = if (installed) "/etc/letsencrypt/live/$domain/privkey.pem" else null
                    )
                )
            }
        }
    }
}
