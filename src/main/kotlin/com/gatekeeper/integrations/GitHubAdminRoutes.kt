package com.gatekeeper.integrations

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.integrations.domain.repository.GitHubCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubInstallationStore
import com.gatekeeper.security.SecretValueCipher
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get

@Serializable
private data class GitHubCredentialRequest(val value: String)

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
    val auditRepository = get<AuditRepositoryPort>()
    val credentialStore = get<GitHubCredentialStore>()
    val installationStore = get<GitHubInstallationStore>()
    val githubAppClient = get<GitHubAppClient>()
    routing {
        authenticate("auth-jwt") {
            put("/api/admin/github/credentials/{type}") {
                val type = call.parameters["type"]
                val body = runCatching { call.receive<GitHubCredentialRequest>() }.getOrNull()
                val validValue = when (type) {
                    GitHubCredentialStore.WEBHOOK_SECRET -> body?.value?.isNotBlank() == true && body.value.length <= 4096
                    GitHubCredentialStore.APP_PRIVATE_KEY -> body?.value?.let {
                        (it.contains("BEGIN PRIVATE KEY") || it.contains("BEGIN RSA PRIVATE KEY")) && it.length <= 65536
                    } == true
                    else -> false
                }
                if (!validValue || body == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_github_credential", "Provide a valid GitHub webhook secret or App private key")
                    return@put
                }
                if (!SecretValueCipher.isConfigured()) {
                    call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
                    return@put
                }
                val credential = credentialStore.save(type!!, body.value)
                auditRepository.write(null, "github_credential_updated", "admin", "type=$type version=${credential.version}")
                call.respond(mapOf(
                    "id" to credential.id.toString(), "type" to type, "configured" to true,
                    "version" to credential.version, "writeOnly" to true
                ))
            }
            get("/api/admin/github/status") {
                val installation = installationStore.find()
                call.respond(
                    GitHubStatusResponse(
                        configured = githubAppClient.isConfigured(),
                        // Environment credentials configure the worker, but do not prove
                        // that this admin completed the GitHub App installation flow.
                        connected = installation != null && githubAppClient.isConfigured(),
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
                val repositories = runCatching { githubAppClient.repositories(query) }.getOrElse {
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
                installationStore.createPendingState(state)
                val separator = if (url.contains("?")) "&" else "?"
                call.respond(GitHubInstallUrlResponse("$url${separator}state=$state", AppConfig.githubCallbackUrl.takeIf { it.isNotBlank() }))
            }
            delete("/api/admin/github/installation") {
                installationStore.clear()
                call.respond(mapOf("status" to "unlinked"))
            }
        }

        get("/api/admin/github/callback") {
            handleGitHubCallback(call, installationStore, githubAppClient)
        }
        get("/api/integrations/github/callback") {
            handleGitHubCallback(call, installationStore, githubAppClient)
        }
    }
}

private suspend fun handleGitHubCallback(call: ApplicationCall, installationStore: GitHubInstallationStore, githubAppClient: GitHubAppClient) {
            val installationId = call.request.queryParameters["installation_id"]?.toLongOrNull()
            val setupAction = call.request.queryParameters["setup_action"]
            val state = call.request.queryParameters["state"]
            val redirect = AppConfig.frontendBaseUrl
                .trimEnd('/')
                .takeIf { it.isNotBlank() }
                ?.let { "$it/app/settings/profile" }
                ?: "/app/settings/profile"
            if (installationId == null || setupAction == "cancel" || state == null || !installationStore.consumePendingState(state)) {
                call.respondRedirect("$redirect?github=cancelled")
                return
            }
            if (AppConfig.githubAppId == null || !githubAppClient.hasPrivateKeyConfigured()) {
                call.respondRedirect("$redirect?github=unconfigured")
                return
            }
            installationStore.save(installationId, call.request.queryParameters["account_login"], call.request.queryParameters["account_type"])
            call.respondRedirect("$redirect?github=authorized&installation_id=$installationId")
}
