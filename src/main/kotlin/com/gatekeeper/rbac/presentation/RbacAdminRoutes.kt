package com.gatekeeper.rbac.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.rbac.application.RbacApplicationService
import com.gatekeeper.rbac.domain.RoleDraft
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get

fun Application.configureRbacAdminRoutes() {
    val rbac = get<RbacApplicationService>()
    val audit = get<AuditRepository>()
    routing {
        authenticate("auth-jwt") {
            install(RbacAuthorizationPlugin)
            get("/api/admin/rbac/permissions") {
                call.respond(rbac.permissions().map { PermissionResponse(it.code, it.category, it.action, it.description) })
            }
            get("/api/admin/rbac/roles") {
                call.respond(rbac.roles().map { it.toResponse() })
            }
            post("/api/admin/rbac/roles") {
                val body = call.receive<CreateRoleRequest>()
                val actor = call.principal<JWTPrincipal>()?.payload?.subject.orEmpty()
                val role = rbac.createRole(actor, RoleDraft(body.key, body.displayName, body.description, body.permissions))
                audit.write(null, "rbac_role_created", actor, "role=${role.key} permissions=${role.permissions.size}")
                call.respond(HttpStatusCode.Created, role.toResponse())
            }
            get("/api/admin/rbac/roles/{key}") {
                val key = call.parameters["key"].orEmpty()
                call.respond(rbac.role(key).toResponse())
            }
            put("/api/admin/rbac/roles/{key}") {
                val body = call.receive<UpdateRoleRequest>()
                val actor = call.principal<JWTPrincipal>()?.payload?.subject.orEmpty()
                val key = call.parameters["key"].orEmpty()
                val role = rbac.updateRole(actor, key, body.displayName, body.description, body.active, body.permissions)
                audit.write(null, "rbac_role_updated", actor, "role=${role.key} permissions=${role.permissions.size}")
                call.respond(role.toResponse())
            }
            delete("/api/admin/rbac/roles/{key}") {
                val actor = call.principal<JWTPrincipal>()?.payload?.subject.orEmpty()
                val key = call.parameters["key"].orEmpty()
                rbac.deleteRole(key)
                audit.write(null, "rbac_role_deleted", actor, "role=$key")
                call.respond(mapOf("status" to "role_deleted", "key" to key))
            }
        }
    }
}

private fun com.gatekeeper.rbac.domain.AccessRole.toResponse() = RoleResponse(
    id = id.toString(),
    key = key,
    displayName = displayName,
    description = description,
    superAdmin = superAdmin,
    active = active,
    permissions = permissions,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
