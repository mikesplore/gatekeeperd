package com.gatekeeper.integrations

import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import org.koin.ktor.ext.get




fun Application.configureGitHubAdminRoutes() {
    val github = get<GitHubAdminApplicationService>()
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            put("/api/admin/github/credentials/{type}") {
                val body = runCatching { call.receive<GitHubCredentialRequest>() }.getOrNull()
                    ?: throw com.gatekeeper.api.HttpApiException(io.ktor.http.HttpStatusCode.BadRequest, "invalid_request", "Invalid credential request")
                call.respond(github.updateCredential(call.parameters["type"], body.value))
            }
            get("/api/admin/github/status") {
                call.respond(github.status())
            }
            get("/api/admin/github/repositories") {
                val query = call.request.queryParameters["q"].orEmpty()
                call.respond(github.repositories(query))
            }
            get("/api/admin/github/install-url") {
                call.respond(github.installUrl())
            }
            delete("/api/admin/github/installation") {
                github.unlink()
                call.respond(mapOf("status" to "unlinked"))
            }
        }

        get("/api/admin/github/callback") {
            call.respondRedirect(github.callback(
                call.request.queryParameters["installation_id"]?.toLongOrNull(),
                call.request.queryParameters["setup_action"],
                call.request.queryParameters["state"],
                call.request.queryParameters["account_login"],
                call.request.queryParameters["account_type"]
            ))
        }
        get("/api/integrations/github/callback") {
            call.respondRedirect(github.callback(
                call.request.queryParameters["installation_id"]?.toLongOrNull(),
                call.request.queryParameters["setup_action"],
                call.request.queryParameters["state"],
                call.request.queryParameters["account_login"],
                call.request.queryParameters["account_type"]
            ))
        }
    }
}
