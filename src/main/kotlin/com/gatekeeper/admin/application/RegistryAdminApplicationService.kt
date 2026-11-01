package com.gatekeeper.admin.application


import com.gatekeeper.admin.model.*
import com.gatekeeper.api.HttpApiException
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialStore
import com.gatekeeper.security.SecretValueCipher
import io.ktor.http.HttpStatusCode
import java.util.UUID

class RegistryAdminApplicationService(
    private val auditRepository: AuditRepository,
    private val providerCredentials: ProviderCredentialStore,
    private val registryCredentials: RegistryCredentialStore
) {
    fun listProviderCredentials(provider: String?, type: String?): List<ProviderCredentialMetadataResponse> {
        if ((provider != null && provider !in setOf("docker", "github")) ||
            (type != null && type !in setOf("registry", "app_private_key", "webhook_secret"))) {
            throw HttpApiException(HttpStatusCode.BadRequest, "invalid_credential_filter", "Credential provider or type is invalid")
        }
        return providerCredentials.listMetadata(provider, type).map { it.toMetadataResponse() }
    }

    fun providerCredential(idValue: String?): ProviderCredentialMetadataResponse {
        val id = idValue?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw HttpApiException(HttpStatusCode.BadRequest, "invalid_credential_id", "Invalid credential ID")
        val metadata = providerCredentials.findMetadata(id)
            ?: throw HttpApiException(HttpStatusCode.NotFound, "credential_not_found", "Provider credential not found")
        return metadata.toMetadataResponse()
    }

    fun registries(): List<RegistryMetadataResponse> =
        registryCredentials.list().map { RegistryMetadataResponse(it.first, it.second, true) }

    fun saveRegistry(registryValue: String?, body: RegistryCredentialRequest?): RegistryCredentialResponse {
        val registry = registryValue?.trim()?.lowercase()
        if (registry.isNullOrBlank() || !registry.matches(Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")) ||
            body == null || body.username.isBlank() || body.password.isBlank()) {
            throw HttpApiException(HttpStatusCode.BadRequest, "invalid_registry_credentials", "Registry, username, and password are required")
        }
        if (!SecretValueCipher.isConfigured()) {
            throw HttpApiException(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
        }
        val credential = registryCredentials.save(registry, body.username, body.password, body.displayName?.trim()?.takeIf(String::isNotBlank))
        auditRepository.write(null, "registry_credentials_updated", "admin", "registry=$registry username=${body.username} version=${credential.version}")
        return RegistryCredentialResponse(registry, body.username, true, credential.id.toString(), credential.version)
    }

    fun deleteRegistry(registryValue: String?): Map<String, Any> {
        val registry = registryValue?.trim()?.lowercase().orEmpty()
        if (!registryCredentials.delete(registry)) {
            throw HttpApiException(HttpStatusCode.NotFound, "registry_credentials_not_found", "Registry credentials not found")
        }
        auditRepository.write(null, "registry_credentials_deleted", "admin", "registry=$registry")
        return mapOf("registry" to registry, "configured" to false)
    }
}
