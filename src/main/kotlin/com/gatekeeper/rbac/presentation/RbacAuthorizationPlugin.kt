package com.gatekeeper.rbac.presentation

import com.gatekeeper.api.HttpApiException
import com.gatekeeper.rbac.application.RbacApplicationService
import com.gatekeeper.rbac.application.RbacPermissionPolicy
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.koin.ktor.ext.get

val RbacAuthorizationPlugin = createRouteScopedPlugin("RbacAuthorization") {
    on(AuthenticationChecked) { call: ApplicationCall ->
        val principal = call.principal<JWTPrincipal>()
            ?: throw HttpApiException(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
        val subject = principal.payload.subject?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            ?: throw HttpApiException(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
        val permissions = RbacPermissionPolicy.permissions(call.request.httpMethod, call.request.path())
            ?: throw HttpApiException(HttpStatusCode.Forbidden, "permission_not_configured", "No permission policy is configured for this admin route")
        val authorization = try {
            val rbac = call.application.get<RbacApplicationService>()
            permissions.all { rbac.authorize(subject, it) }
        } catch (_: Exception) {
            throw HttpApiException(HttpStatusCode.ServiceUnavailable, "authorization_unavailable", "Authorization is temporarily unavailable")
        }
        if (!authorization) {
            throw HttpApiException(HttpStatusCode.Forbidden, "insufficient_permissions", "You do not have permission to perform this action")
        }
    }
}
