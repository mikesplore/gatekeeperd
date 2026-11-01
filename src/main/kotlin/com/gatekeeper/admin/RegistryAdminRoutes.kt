package com.gatekeeper.admin

import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialStore
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureRegistryAdminRoutes() {
    val service = RegistryAdminApplicationService(
        get<AuditRepository>(), get<ProviderCredentialStore>(), get<RegistryCredentialStore>()
    )
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/provider-credentials") {
                call.respond(service.listProviderCredentials(
                    call.request.queryParameters["provider"]?.trim()?.lowercase(),
                    call.request.queryParameters["type"]?.trim()?.lowercase()
                ))
            }
            get("/api/admin/provider-credentials/{id}") {
                call.respond(service.providerCredential(call.parameters["id"]))
            }
            get("/api/admin/registries") { call.respond(service.registries()) }
            put("/api/admin/registries/{registry}") {
                val body = runCatching { call.receive<RegistryCredentialRequest>() }.getOrNull()
                call.respond(service.saveRegistry(call.parameters["registry"], body))
            }
            delete("/api/admin/registries/{registry}") {
                call.respond(service.deleteRegistry(call.parameters["registry"]))
            }
        }
    }
}
