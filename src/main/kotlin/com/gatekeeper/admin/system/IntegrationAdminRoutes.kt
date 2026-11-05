package com.gatekeeper.admin.system


import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import kotlinx.serialization.Serializable
import io.ktor.server.request.receive
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.api.HttpApiException
import io.ktor.http.HttpStatusCode

@Serializable private data class ScribedSettingsRequest(val baseUrl: String, val apiToken: String)

fun Application.configureIntegrationAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            get("/api/admin/integrations/scribed") {
                call.respond(get<ScribedIntegrationClient>().documentSettingsStatus())
            }
            put("/api/admin/integrations/scribed") {
                val body = runCatching { call.receive<ScribedSettingsRequest>() }.getOrNull()
                    ?: throw HttpApiException(HttpStatusCode.BadRequest, "invalid_scribed_settings", "Scribed settings are invalid")
                if (!SecretValueCipher.isConfigured()) throw HttpApiException(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
                runCatching { get<ScribedIntegrationClient>().saveDocumentSettings(body.baseUrl, body.apiToken) }
                    .getOrElse { throw HttpApiException(HttpStatusCode.BadRequest, "invalid_scribed_settings", it.message ?: "Scribed settings are invalid") }
                call.respond(get<ScribedIntegrationClient>().documentSettingsStatus())
            }
            post("/api/admin/integrations/scribed/health") {
                val result = get<ScribedIntegrationClient>().healthCheck()
                call.respond(result)
            }
        }
    }
}
