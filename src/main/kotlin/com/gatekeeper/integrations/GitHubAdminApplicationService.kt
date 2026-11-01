package com.gatekeeper.integrations

import com.gatekeeper.api.HttpApiException
import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.integrations.domain.repository.GitHubCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubInstallationStore
import com.gatekeeper.security.SecretValueCipher
import io.ktor.http.HttpStatusCode
import java.util.UUID

class GitHubAdminApplicationService(
    private val settings: AppSettings,
    private val credentialStore: GitHubCredentialStore,
    private val installationStore: GitHubInstallationStore,
    private val githubAppClient: GitHubAppClient,
    private val auditRepository: AuditRepository
) {
    fun updateCredential(type: String?, value: String): Map<String, Any> {
        val valid = when (type) {
            GitHubCredentialStore.WEBHOOK_SECRET -> value.isNotBlank() && value.length <= 4096
            GitHubCredentialStore.APP_PRIVATE_KEY ->
                (value.contains("BEGIN PRIVATE KEY") || value.contains("BEGIN RSA PRIVATE KEY")) && value.length <= 65536
            else -> false
        }
        if (!valid || type == null) throw HttpApiException(HttpStatusCode.BadRequest, "invalid_github_credential", "Provide a valid GitHub webhook secret or App private key")
        if (!SecretValueCipher.isConfigured()) throw HttpApiException(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
        val credential = credentialStore.save(type, value)
        auditRepository.write(null, "github_credential_updated", "admin", "type=$type version=${credential.version}")
        return mapOf("id" to credential.id.toString(), "type" to type, "configured" to true, "version" to credential.version, "writeOnly" to true)
    }

    fun status(): GitHubStatusResponse {
        val installation = installationStore.find()
        return GitHubStatusResponse(
            configured = githubAppClient.isConfigured(),
            connected = installation != null && githubAppClient.isConfigured(),
            appId = settings.github.appId,
            installationId = installation?.installationId ?: settings.github.installationId,
            accountLogin = installation?.accountLogin,
            accountType = installation?.accountType,
            appSlug = settings.github.appSlug.takeIf { it.isNotBlank() }
        )
    }

    suspend fun repositories(query: String) = try {
        githubAppClient.repositories(query)
    } catch (error: Exception) {
        throw HttpApiException(HttpStatusCode.BadGateway, "github_repository_lookup_failed", "Unable to load GitHub repositories")
    }

    fun installUrl(): GitHubInstallUrlResponse {
        val url = settings.github.installUrl.takeIf { it.isNotBlank() }
            ?: settings.github.appSlug.takeIf { it.isNotBlank() }?.let { "https://github.com/apps/$it/installations/new" }
            ?: throw HttpApiException(HttpStatusCode.ServiceUnavailable, "github_app_unconfigured", "GitHub App install URL is not configured")
        val state = UUID.randomUUID().toString()
        installationStore.createPendingState(state)
        val separator = if (url.contains("?")) "&" else "?"
        return GitHubInstallUrlResponse("$url${separator}state=$state", settings.github.callbackUrl.takeIf { it.isNotBlank() })
    }

    fun unlink() = installationStore.clear()

    fun callback(installationId: Long?, setupAction: String?, state: String?, accountLogin: String?, accountType: String?): String {
        val redirect = settings.integrations.frontendBaseUrl.trimEnd('/').takeIf { it.isNotBlank() }
            ?.let { "$it/app/settings/profile" } ?: "/app/settings/profile"
        if (installationId == null || setupAction == "cancel" || state == null || !installationStore.consumePendingState(state)) {
            return "$redirect?github=cancelled"
        }
        if (settings.github.appId == null || !githubAppClient.hasPrivateKeyConfigured()) return "$redirect?github=unconfigured"
        installationStore.save(installationId, accountLogin, accountType)
        return "$redirect?github=authorized&installation_id=$installationId"
    }
}
