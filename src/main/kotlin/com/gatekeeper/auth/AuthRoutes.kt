package com.gatekeeper.auth

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.Users
import com.gatekeeper.plugins.JwtConfig
import com.gatekeeper.plugins.RedisService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.auth.AuthRoutes")
private const val LOGIN_WINDOW_SECONDS = 15 * 60
private const val LOGIN_ATTEMPT_LIMIT = 5

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class UserProfileResponse(
    val email: String,
    val role: String,
    val createdAt: String
)

fun Application.configureAuthRoutes() {
    routing {
        post("/api/auth/login") {
            val body = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                return@post
            }

            if (body.email.isBlank() || body.password.isBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Email and password are required")
                return@post
            }

            val email = body.email.lowercase().trim()
            if (!InputValidators.isValidEmail(email)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Enter a valid email address")
                return@post
            }

            val clientIp = call.request.local.remoteHost
            val emailKey = "auth:login:email:$email"
            val ipKey = "auth:login:ip:$clientIp"
            val rateLimited = try {
                val emailAttempts = RedisService.incrementWithExpiry(emailKey, LOGIN_WINDOW_SECONDS) ?: 0
                val ipAttempts = RedisService.incrementWithExpiry(ipKey, LOGIN_WINDOW_SECONDS) ?: 0
                emailAttempts > LOGIN_ATTEMPT_LIMIT || ipAttempts > LOGIN_ATTEMPT_LIMIT
            } catch (e: Exception) {
                logger.warn("Login rate limiter unavailable: ${e.message}")
                false
            }
            if (rateLimited) {
                call.response.headers.append(HttpHeaders.RetryAfter, LOGIN_WINDOW_SECONDS.toString())
                call.respondError(HttpStatusCode.TooManyRequests, "login_rate_limited", "Too many login attempts. Try again later.")
                return@post
            }

            val user = transaction {
                Users.selectAll().where { Users.email eq email }
                    .singleOrNull()
            }

            if (user == null) {
                logger.warn("Login attempt for unknown email: $email")
                call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Invalid email or password")
                return@post
            }

            val passwordHash = user[Users.passwordHash]
            if (!BCrypt.checkpw(body.password, passwordHash)) {
                logger.warn("Failed login attempt for: $email")
                call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Invalid email or password")
                return@post
            }

            val token = JwtConfig.createToken(email, user[Users.role])
            runCatching { RedisService.delete(emailKey) }
            logger.info("Successful login: $email (account created ${user[Users.createdAt]})")
            call.respond(LoginResponse(token))
        }

        authenticate("auth-jwt") {
            post("/api/auth/password") {
                val principal = call.principal<JWTPrincipal>()
                val body = try {
                    call.receive<ChangePasswordRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Current and new passwords are required")
                    return@post
                }
                if (principal == null || body.currentPassword.isBlank() || body.newPassword.length < 8) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "New password must be at least 8 characters")
                    return@post
                }
                val email = principal.payload.subject.lowercase().trim()
                val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
                if (user == null || !BCrypt.checkpw(body.currentPassword, user[Users.passwordHash])) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Current password is incorrect")
                    return@post
                }
                val hash = BCrypt.hashpw(body.newPassword, BCrypt.gensalt(12))
                transaction {
                    Users.update({ Users.email eq email }) { it[Users.passwordHash] = hash }
                }
                call.respond(mapOf("status" to "password_changed"))
            }

            post("/api/auth/logout") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
                    return@post
                }
                val jti = principal.payload.id
                val expiresAt = principal.payload.expiresAt?.time ?: 0L
                val ttlSeconds = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
                if (jti.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.Unauthorized, "invalid_token", "Token cannot be revoked")
                    return@post
                }
                RedisService.set("auth:revoked:$jti", "1", ttlSeconds.toInt())
                call.respond(mapOf("status" to "logged_out"))
            }

            get("/api/auth/me") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
                    return@get
                }
                val email = principal.payload.subject

                val user = transaction {
                    Users.selectAll().where { Users.email eq email.lowercase().trim() }
                        .singleOrNull()
                }
                if (user == null) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found")
                    return@get
                }

                call.respond(
                    UserProfileResponse(
                        email = user[Users.email],
                        role = user[Users.role],
                        createdAt = user[Users.createdAt].toString()
                    )
                )
            }
        }
    }
}
