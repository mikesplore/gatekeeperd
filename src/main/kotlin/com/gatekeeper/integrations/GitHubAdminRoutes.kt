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
import kotlinx.serialization.Serializable

@Serializable
private data class GitHubStatusResponse(
    val configured: Boolean,
    val connected: Boolean,
    val appId: Long? = null,
    val installationId: Long? = null,
    val accountLogin: String? = null,
    val accountType: String? = null,
    val appSlug: String? = null,
)

@Serializable
private data class GitHubInstallUrlResponse(val url: String, val callbackUrl: String? = null)

fun Application.configureGitHubAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/github/status") {
                val installation = GitHubAppInstallationRepository.find()
                call.respond(
                    GitHubStatusResponse(
                        configured = GitHubAppClient.isConfigured(),
                        // Environment credentials configure the worker, but do not prove
                        // that this admin completed the GitHub App installation flow.
                        connected = installation != null && GitHubAppClient.isConfigured(),
                        appId = AppConfig.githubAppId,
                        installationId = installation?.installationId ?: AppConfig.githubAppInstallationId,
                        accountLogin = installation?.accountLogin,
                        accountType = installation?.accountType,
                        appSlug = AppConfig.githubAppSlug.takeIf { it.isNotBlank() },
                    )
                )
            }
            get("/api/admin/github/repositories") {
                val query = call.request.queryParameters["q"].orEmpty()
                val repositories = runCatching { GitHubAppClient.repositories(query) }.getOrElse {
                    application.log.warn("GitHub repository lookup failed", it)
                    call.respondError(HttpStatusCode.BadGateway, "github_repository_lookup_failed", "Unable to load GitHub repositories")
                    return@get
                }
                call.respond(repositories)
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
                call.respond(GitHubInstallUrlResponse("$url${separator}state=$state", AppConfig.githubCallbackUrl.takeIf { it.isNotBlank() }))
            }
            delete("/api/admin/github/installation") {
                GitHubAppInstallationRepository.clear()
                call.respond(mapOf("status" to "unlinked"))
            }
        }

        get("/api/admin/github/callback") {
            handleGitHubCallback(call)
        }
        get("/api/integrations/github/callback") {
            handleGitHubCallback(call)
        }
    }
}

private suspend fun handleGitHubCallback(call: ApplicationCall) {
            val installationId = call.request.queryParameters["installation_id"]?.toLongOrNull()
            val setupAction = call.request.queryParameters["setup_action"]
            val state = call.request.queryParameters["state"]
            val redirect = AppConfig.frontendBaseUrl
                .trimEnd('/')
                .takeIf { it.isNotBlank() }
                ?.let { "$it/app/settings/profile" }
                ?: "/app/settings/profile"
            if (installationId == null || setupAction == "cancel" || state == null || !GitHubAppInstallationRepository.consumePendingState(state)) {
                call.respondRedirect("$redirect?github=cancelled")
                return
            }
            if (AppConfig.githubAppId == null || AppConfig.githubAppPrivateKeyPath.isBlank()) {
                call.respondRedirect("$redirect?github=unconfigured")
                return
            }
            GitHubAppInstallationRepository.save(installationId, call.request.queryParameters["account_login"], call.request.queryParameters["account_type"])
            call.respondRedirect("$redirect?github=authorized&installation_id=$installationId")
}
