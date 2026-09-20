package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.RegistryCredentialRepository
import com.gatekeeper.security.SecretValueCipher
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable private data class RegistryCredentialRequest(val username: String, val password: String)
fun Application.configureRegistryAdminRoutes() {
    routing { authenticate("auth-jwt") {
        get("/api/admin/registries") { call.respond(RegistryCredentialRepository.list().map { mapOf("registry" to it.first, "username" to it.second, "configured" to true) }) }
        put("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase()
            val body = runCatching { call.receive<RegistryCredentialRequest>() }.getOrNull()
            if (registry.isNullOrBlank() || !registry.matches(Regex("^[a-z0-9.-]+(:[0-9]{1,5})?$")) || body == null || body.username.isBlank() || body.password.isBlank()) { call.respondError(HttpStatusCode.BadRequest, "invalid_registry_credentials", "Registry, username, and password are required"); return@put }
            if (!SecretValueCipher.isConfigured()) { call.respondError(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured"); return@put }
            RegistryCredentialRepository.save(registry, body.username, body.password)
            AuditRepository.write(null, "registry_credentials_updated", "admin", "registry=$registry username=${body.username}")
            call.respond(mapOf("registry" to registry, "username" to body.username, "configured" to true))
        }
        delete("/api/admin/registries/{registry}") {
            val registry = call.parameters["registry"]?.trim()?.lowercase() ?: ""
            if (!RegistryCredentialRepository.delete(registry)) { call.respondError(HttpStatusCode.NotFound, "registry_credentials_not_found", "Registry credentials not found"); return@delete }
            AuditRepository.write(null, "registry_credentials_deleted", "admin", "registry=$registry")
            call.respond(mapOf("registry" to registry, "configured" to false))
        }
    } }
}
