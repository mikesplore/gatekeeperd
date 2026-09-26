package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.RegistryCredentialRepository
import com.gatekeeper.db.repositories.ProviderCredentialRepository
import com.gatekeeper.db.repositories.ProviderCredentialMetadata
import com.gatekeeper.security.SecretValueCipher
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable private data class RegistryCredentialRequest(val username: String, val password: String)
@Serializable private data class RegistryMetadataResponse(val registry: String, val username: String, val configured: Boolean)
@Serializable private data class RegistryCredentialResponse(
    val registry: String,
    val username: String,
    val configured: Boolean,
    val credentialId: String,
    val version: Int
)
fun Application.configureRegistryAdminRoutes() {
    routing { authenticate("auth-jwt") {
        get("/api/admin/provider-credentials") {
            val provider = call.request.queryParameters["provider"]?.trim()?.lowercase()
            val type = call.request.queryParameters["type"]?.trim()?.lowercase()
            if ((provider != null && provider !in setOf("docker", "github")) ||
                (type != null && type !in setOf("registry", "app_private_key", "webhook_secret"))) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_credential_filter", "Credential provider or type is invalid")
                return@get
            }
            call.respond(ProviderCredentialRepository.listMetadata(provider, type).map { it.toMetadataResponse() })
        }
        get("/api/admin/provider-credentials/{id}") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_credential_id", "Invalid credential ID")
                return@get
            }
            val metadata = ProviderCredentialRepository.findMetadata(id)
            if (metadata == null) {
                call.respondError(HttpStatusCode.NotFound, "credential_not_found", "Provider credential not found")
                return@get
            }
            call.respond(metadata.toMetadataResponse())
        }
        get("/api/admin/registries") { call.respond(RegistryCredentialRepository.list().map { RegistryMetadataResponse(it.first, it.second, true) }) }
        put("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase()
            val body = runCatching { call.receive<RegistryCredentialRequest>() }.getOrNull()
            if (registry.isNullOrBlank() || !registry.matches(Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")) || body == null || body.username.isBlank() || body.password.isBlank()) { call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credentials", "Registry, username, and password are required"); return@put }
            if (!SecretValueCipher.isConfigured()) { call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured"); return@put }
            val credential = RegistryCredentialRepository.save(registry, body.username, body.password)
            AuditRepository.write(null, "registry_credentials_updated", "admin", "registry=$registry username=${body.username} version=${credential.version}")
            call.respond(RegistryCredentialResponse(registry, body.username, true, credential.id.toString(), credential.version))
        }
        delete("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase() ?: ""
            if (!RegistryCredentialRepository.delete(registry)) { call.respondError(HttpStatusCode.NotFound, "registry_credentials_not_found", "Registry credentials not found"); return@delete }
            AuditRepository.write(null, "registry_credentials_deleted", "admin", "registry=$registry")
            call.respond(mapOf("registry" to registry, "configured" to false))
        }
    } }
}

@Serializable
private data class ProviderCredentialMetadataResponse(
    val id: String,
    val provider: String,
    val type: String,
    val displayName: String,
    val scope: String,
    val version: Int,
    val current: Boolean,
    val rotatedAt: String?,
    val rotatedBy: String?,
    val supersededAt: String?,
    val supersededById: String?,
    val createdAt: String,
    val createdBy: String?,
    val updatedAt: String,
    val updatedBy: String?
)

private fun ProviderCredentialMetadata.toMetadataResponse() = ProviderCredentialMetadataResponse(
    id.toString(), provider, credentialType, displayName, scope, version, current,
    rotatedAt?.toString(), rotatedBy, supersededAt?.toString(), supersededById?.toString(),
    createdAt.toString(), createdBy, updatedAt.toString(), updatedBy
)
