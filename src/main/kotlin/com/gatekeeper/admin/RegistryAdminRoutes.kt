package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialStore
import com.gatekeeper.security.SecretValueCipher
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID
import org.koin.ktor.ext.get

fun Application.configureRegistryAdminRoutes() {
    val auditRepository = get<AuditRepositoryPort>()
    val providerCredentials = get<ProviderCredentialStore>()
    val registryCredentials = get<RegistryCredentialStore>()
    routing { authenticate("auth-jwt") {
        get("/api/admin/provider-credentials") {
            val provider = call.request.queryParameters["provider"]?.trim()?.lowercase()
            val type = call.request.queryParameters["type"]?.trim()?.lowercase()
            if ((provider != null && provider !in setOf("docker", "github")) ||
                (type != null && type !in setOf("registry", "app_private_key", "webhook_secret"))) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_credential_filter", "Credential provider or type is invalid")
                return@get
            }
            call.respond(providerCredentials.listMetadata(provider, type).map { it.toMetadataResponse() })
        }
        get("/api/admin/provider-credentials/{id}") {
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_credential_id", "Invalid credential ID")
                return@get
            }
            val metadata = providerCredentials.findMetadata(id)
            if (metadata == null) {
                call.respondError(HttpStatusCode.NotFound, "credential_not_found", "Provider credential not found")
                return@get
            }
            call.respond(metadata.toMetadataResponse())
        }
        get("/api/admin/registries") { call.respond(registryCredentials.list().map { RegistryMetadataResponse(it.first, it.second, true) }) }
        put("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase()
            val body = runCatching { call.receive<RegistryCredentialRequest>() }.getOrNull()
            if (registry.isNullOrBlank() || !registry.matches(Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")) || body == null || body.username.isBlank() || body.password.isBlank()) { call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credentials", "Registry, username, and password are required"); return@put }
            if (!SecretValueCipher.isConfigured()) { call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured"); return@put }
            val credential = registryCredentials.save(registry, body.username, body.password, body.displayName?.trim()?.takeIf(String::isNotBlank))
            auditRepository.write(null, "registry_credentials_updated", "admin", "registry=$registry username=${body.username} version=${credential.version}")
            call.respond(RegistryCredentialResponse(registry, body.username, true, credential.id.toString(), credential.version))
        }
        delete("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase() ?: ""
            if (!registryCredentials.delete(registry)) { call.respondError(HttpStatusCode.NotFound, "registry_credentials_not_found", "Registry credentials not found"); return@delete }
            auditRepository.write(null, "registry_credentials_deleted", "admin", "registry=$registry")
            call.respond(mapOf("registry" to registry, "configured" to false))
        }
    } }
}
