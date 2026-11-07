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

@Serializable private data class ScribedClientRegistrationRequest(
    val baseUrl: String,
    val adminToken: String,
    val name: String,
    val address: String? = null,
    val contactEmail: String? = null,
    val phone: String? = null
)

fun Application.configureIntegrationAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            get("/api/admin/integrations/scribed") {
                call.respond(get<ScribedIntegrationClient>().documentSettingsStatus())
            }
            post("/api/admin/integrations/scribed/import-env") {
                runCatching { get<ScribedIntegrationClient>().importEnvironmentClientToken() }
                    .getOrElse { error ->
                        val status = when (error) {
                            is IllegalArgumentException -> HttpStatusCode.BadRequest
                            is IllegalStateException -> if (error.message == "Secret encryption is not configured") HttpStatusCode.ServiceUnavailable else HttpStatusCode.BadGateway
                            else -> HttpStatusCode.BadGateway
                        }
                        val code = when (status) {
                            HttpStatusCode.BadRequest -> "scribed_environment_invalid"
                            HttpStatusCode.ServiceUnavailable -> "secrets_unconfigured"
                            else -> "scribed_token_import_failed"
                        }
                        throw HttpApiException(status, code, error.message ?: "Could not import the Scribed API client token")
                    }
                call.respond(get<ScribedIntegrationClient>().documentSettingsStatus())
            }
            post("/api/admin/integrations/scribed/register") {
                val body = runCatching { call.receive<ScribedClientRegistrationRequest>() }.getOrNull()
                    ?: throw HttpApiException(HttpStatusCode.BadRequest, "invalid_scribed_settings", "Scribed client registration details are invalid")
                if (!SecretValueCipher.isConfigured()) throw HttpApiException(HttpStatusCode.ServiceUnavailable, "secrets_unconfigured", "Secret encryption is not configured")
                runCatching {
                    get<ScribedIntegrationClient>().registerDocumentClient(
                        body.baseUrl, body.adminToken,
                        ScribedIntegrationClient.ScribedClientProfile(body.name, body.address, body.contactEmail, body.phone)
                    )
                }.getOrElse { error ->
                    val status = if (error is IllegalArgumentException) HttpStatusCode.BadRequest else HttpStatusCode.BadGateway
                    throw HttpApiException(status, "scribed_registration_failed", error.message ?: "Scribed client registration failed")
                }
                call.respond(get<ScribedIntegrationClient>().documentSettingsStatus())
            }
            post("/api/admin/integrations/scribed/health") {
                val result = get<ScribedIntegrationClient>().healthCheck()
                call.respond(result)
            }
        }
    }
}
