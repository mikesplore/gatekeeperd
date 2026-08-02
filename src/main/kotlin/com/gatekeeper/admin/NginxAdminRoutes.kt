package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.nginx.CertificateInstallRequest
import com.gatekeeper.nginx.CertificateResponse
import com.gatekeeper.nginx.NginxEnableRequest
import com.gatekeeper.nginx.NginxStatusResponse
import com.gatekeeper.nginx.NginxService
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
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.admin.NginxAdminRoutes")

@Serializable
data class NginxEnableResponse(
    val success: Boolean,
    val message: String,
    val config: String? = null
)

@Serializable
data class NginxDisableResponse(
    val success: Boolean,
    val message: String
)

fun Application.configureNginxAdminRoutes() {
    val nginxService = NginxService()

    routing {
        authenticate("auth-jwt") {

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

                val enabled = nginxService.run {
                    val available = java.io.File("/etc/nginx/sites-available/$slug").exists()
                    val enabled = java.io.File("/etc/nginx/sites-enabled/$slug").exists()
                    available && enabled
                }

                val certInstalled = nginxService.isCertificateInstalled(project.domain)

                call.respond(
                    NginxStatusResponse(
                        enabled = enabled,
                        configPath = "/etc/nginx/sites-available/$slug",
                        enabledPath = "/etc/nginx/sites-enabled/$slug",
                        port = project.containerName.split(":").getOrNull(1)?.toIntOrNull(),
                        sslEnabled = certInstalled,
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

                val appPort = project.containerName.split(":").getOrElse(1) { "80" }.toIntOrNull()
                    ?: 80

                if (!nginxService.isPortActive(appPort)) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "port_not_active",
                        "Port $appPort is not active for project $slug. Ensure the container is running."
                    )
                    return@post
                }

                val domain = project.domain.ifBlank {
                    call.respondError(HttpStatusCode.BadRequest, "no_domain", "Project has no domain configured")
                    return@post
                }

                if (body.sslCertificatePath != null && body.sslCertificateKeyPath != null) {
                    val certFile = java.io.File(body.sslCertificatePath)
                    val keyFile = java.io.File(body.sslCertificateKeyPath)
                    if (!certFile.exists() || !keyFile.exists()) {
                        call.respondError(
                            HttpStatusCode.BadRequest,
                            "certificate_not_found",
                            "SSL certificate files not found at specified paths"
                        )
                        return@post
                    }
                }

                val sslEnabled = body.sslCertificatePath != null && body.sslCertificateKeyPath != null
                val config = nginxService.generateNginxConfig(
                    slug = slug,
                    domain = domain,
                    appPort = appPort,
                    sslEnabled = sslEnabled,
                    sslCertificatePath = body.sslCertificatePath,
                    sslCertificateKeyPath = body.sslCertificateKeyPath
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
                        config = config
                    )
                )
            }

            post("/api/admin/nginx/disable/{slug}") {
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

                val project = ProjectRepository.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
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