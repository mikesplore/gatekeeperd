package com.gatekeeper.auth

import com.gatekeeper.config.AppSettings
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.PasswordResetTokens
import com.gatekeeper.db.tables.Users
import com.gatekeeper.integrations.ResendClient
import com.gatekeeper.plugins.JwtConfig
import com.gatekeeper.plugins.RedisService
import com.gatekeeper.security.RecoveryCodeHasher
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.security.Totp
import com.gatekeeper.rbac.application.RbacApplicationService
import com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.get
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.*
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort

private val adminUserRoutesLogger = LoggerFactory.getLogger("com.gatekeeper.auth.AdminUserRoutes")

fun Route.adminUserRoutes(
    authUsers: AuthUserRepository,
    auditRepository: AuditRepositoryPort,
    rbac: RbacApplicationService
) {
    authenticate("auth-jwt") {
        install(RbacAuthorizationPlugin)
        get("/api/admin/users") {
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
            val q = call.request.queryParameters["q"]?.trim()
            val (records, total) = authUsers.listUsers(q, limit, offset.toLong())
            val users = records.map { user ->
                AdminUserResponse(user.id.toString(), user.email, user.role, user.totpEnabled, user.createdAt.toString(), user.active)
            }
            call.respond(AdminUsersPageResponse(users, total, limit, offset, offset + users.size < total))
        }

        post("/api/admin/users") {
            val actor = call.principal<JWTPrincipal>()?.payload?.subject.orEmpty()
            val body = runCatching { call.receive<CreateAdminUserRequest>() }.getOrNull()
            val email = body?.email?.trim()?.lowercase()
            val role = body?.role?.trim()?.lowercase()
            if (body == null || email.isNullOrBlank() || !InputValidators.isValidEmail(email) ||
                body.password.length < 8 || role.isNullOrBlank() || !rbac.roleExists(role)) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "A valid email, password of at least 8 characters, and an existing role are required"
                )
                return@post
            }
            val newEmail = requireNotNull(email)
            val newRole = requireNotNull(role)
            if (!rbac.mayAssignRole(actor, newRole)) {
                call.respondError(HttpStatusCode.Forbidden, "role_assignment_denied", "You cannot assign this role")
                return@post
            }
            val created = runCatching {
                authUsers.createAdmin(newEmail, BCrypt.hashpw(body.password, BCrypt.gensalt(12)), newRole)
            }.getOrElse {
                if (it.message == "duplicate" || AuthSupport.isUniqueConstraintViolation(it)) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "user_exists",
                        "A user with that email already exists"
                    )
                } else {
                    adminUserRoutesLogger.error("Failed to create admin user", it)
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "user_creation_failed",
                        "Unable to create user"
                    )
                }
                return@post
            }
            auditRepository.write(
                null,
                "Admin User Created",
                actor,
                "email=$newEmail role=$newRole two_factor_enabled=false"
            )
            call.respond(
                HttpStatusCode.Created,
                AdminUserResponse(created.id.toString(), newEmail, newRole, false, created.createdAt.toString())
            )
        }

        patch("/api/admin/users/{id}") {
            val actor = call.principal<JWTPrincipal>()?.payload?.subject?.lowercase()?.trim().orEmpty()
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val body = runCatching { call.receive<UpdateAdminUserRequest>() }.getOrNull()
            if (id == null || body == null || (body.email == null && body.role == null && body.active == null)) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "A valid user update is required"
                ); return@patch
            }
            val email = body.email?.trim()?.lowercase()
            val role = body.role?.trim()?.lowercase()
            if ((email != null && !InputValidators.isValidEmail(email)) || (role != null && !rbac.roleExists(role))) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "Email or role is invalid"
                ); return@patch
            }
            val target = authUsers.findById(id)
            if (body.active == false && target?.email == actor) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "cannot_suspend_self",
                    "You cannot suspend your own account"
                ); return@patch
            }
            if (role != null && target?.role != role && !rbac.mayAssignRole(actor, role)) {
                call.respondError(HttpStatusCode.Forbidden, "role_assignment_denied", "You cannot assign this role")
                return@patch
            }
            if (role != null && target?.role == "admin" && role != "admin" && authUsers.countAdmins() <= 1) {
                call.respondError(HttpStatusCode.Conflict, "last_admin", "The last administrator cannot be demoted")
                return@patch
            }
            val updated = try {
                authUsers.updateAdmin(id, email, role, body.active)
            } catch (error: Exception) {
                if (email != null && AuthSupport.isUniqueConstraintViolation(error)) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "user_exists",
                        "A user with that email already exists"
                    )
                    return@patch
                }
                throw error
            }
            if (updated == null) {
                call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found"); return@patch
            }
            auditRepository.write(
                null,
                "Admin User Updated",
                actor,
                "user_id=$id email=${email ?: "unchanged"} role=${role ?: "unchanged"} active=${body.active ?: "unchanged"}"
            )
            call.respond(mapOf("status" to "user_updated"))
        }

        delete("/api/admin/users/{id}") {
            val actor = call.principal<JWTPrincipal>()?.payload?.subject?.lowercase()?.trim().orEmpty()
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid user ID"); return@delete
            }
            val target = authUsers.findById(id)
            if (target == null) {
                call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found"); return@delete
            }
            if (target.email == actor) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "cannot_delete_self",
                    "You cannot delete your own account"
                ); return@delete
            }
            if (target.role == "admin" && authUsers.countAdmins() <= 1) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "last_admin",
                    "The last administrator cannot be deleted"
                ); return@delete
            }
            authUsers.deleteAdmin(id)
            auditRepository.write(null, "Admin User Deleted", actor, "user_id=$id email=${target.email}")
            call.respond(mapOf("status" to "user_deleted"))
        }
    }
}
