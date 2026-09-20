package com.gatekeeper.integrations

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.GitHubAppInstallationRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.*
import java.util.UUID

fun Application.configureGitHubAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/github/status") {
                val installation = GitHubAppInstallationRepository.find()
                call.respond(
                    mapOf(
                        "configured" to GitHubAppClient.isConfigured(),
                        "appId" to AppConfig.githubAppId,
                        "installationId" to (installation?.installationId ?: AppConfig.githubAppInstallationId),
                        "accountLogin" to installation?.accountLogin,
                        "accountType" to installation?.accountType,
                        "appSlug" to AppConfig.githubAppSlug.takeIf { it.isNotBlank() },
                        "connected" to (installation != null || GitHubAppClient.isConfigured()),
                    )
                )
            }
            get("/api/admin/github/install-url") {
                val url = AppConfig.githubAppInstallUrl.takeIf { it.isNotBlank() }
                    ?: AppConfig.githubAppSlug.takeIf { it.isNotBlank() }?.let { "https://github.com/apps/$it/installations/new" }
                if (url == null) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "github_app_unconfigured", "GitHub App install URL is not configured")
                    return@get
                }
                val state = UUID.randomUUID().toString()
                GitHubAppInstallationRepository.createPendingState(state)
                val separator = if (url.contains("?")) "&" else "?"
                call.respond(mapOf("url" to "$url${separator}state=$state", "callbackUrl" to AppConfig.githubCallbackUrl.takeIf { it.isNotBlank() }))
            }
        }

        get("/api/integrations/github/callback") {
            val installationId = call.request.queryParameters["installation_id"]?.toLongOrNull()
            val setupAction = call.request.queryParameters["setup_action"]
            val state = call.request.queryParameters["state"]
            val redirect = AppConfig.frontendBaseUrl.ifBlank { "/app/settings/profile" }
            if (installationId == null || setupAction == "cancel" || state == null || !GitHubAppInstallationRepository.consumePendingState(state)) {
                call.respondRedirect("$redirect?github=cancelled")
                return@get
            }
            if (AppConfig.githubAppId == null || AppConfig.githubAppPrivateKeyPath.isBlank()) {
                call.respondRedirect("$redirect?github=unconfigured")
                return@get
            }
            GitHubAppInstallationRepository.save(installationId, call.request.queryParameters["account_login"], call.request.queryParameters["account_type"])
            call.respondRedirect("$redirect?github=authorized&installation_id=$installationId")
        }
    }
}
