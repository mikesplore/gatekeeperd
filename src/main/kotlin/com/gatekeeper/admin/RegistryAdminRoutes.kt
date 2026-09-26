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
        get("/api/admin/registries") { call.respond(RegistryCredentialRepository.list().map { mapOf("registry" to it.first, "username" to it.second, "configured" to true) }) }
        put("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase()
            val body = runCatching { call.receive<RegistryCredentialRequest>() }.getOrNull()
            if (registry.isNullOrBlank() || !registry.matches(Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")) || body == null || body.username.isBlank() || body.password.isBlank()) { call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credentials", "Registry, username, and password are required"); return@put }
            if (!SecretValueCipher.isConfigured()) { call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured"); return@put }
            val credential = RegistryCredentialRepository.save(registry, body.username, body.password)
            AuditRepository.write(null, "registry_credentials_updated", "admin", "registry=$registry username=${body.username} version=${credential.version}")
            call.respond(mapOf("registry" to registry, "username" to body.username, "configured" to true, "credentialId" to credential.id.toString(), "version" to credential.version))
        }
        delete("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase() ?: ""
            if (!RegistryCredentialRepository.delete(registry)) { call.respondError(HttpStatusCode.NotFound, "registry_credentials_not_found", "Registry credentials not found"); return@delete }
            AuditRepository.write(null, "registry_credentials_deleted", "admin", "registry=$registry")
            call.respond(mapOf("registry" to registry, "configured" to false))
        }
    } }
}

private fun ProviderCredentialMetadata.toMetadataResponse() = mapOf(
    "id" to id.toString(),
    "provider" to provider,
    "type" to credentialType,
    "displayName" to displayName,
    "scope" to scope,
    "version" to version,
    "current" to current,
    "rotatedAt" to rotatedAt?.toString(),
    "rotatedBy" to rotatedBy,
    "supersededAt" to supersededAt?.toString(),
    "supersededById" to supersededById?.toString(),
    "createdAt" to createdAt.toString(),
    "createdBy" to createdBy,
    "updatedAt" to updatedAt.toString(),
    "updatedBy" to updatedBy
)
