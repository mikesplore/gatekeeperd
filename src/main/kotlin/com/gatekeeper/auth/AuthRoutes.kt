package com.gatekeeper.auth

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.Users
import com.gatekeeper.db.tables.PasswordResetTokens
import com.gatekeeper.integrations.ResendClient
import com.gatekeeper.config.AppConfig
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64

private val logger = LoggerFactory.getLogger("com.gatekeeper.auth.AuthRoutes")
private const val LOGIN_WINDOW_SECONDS = 15 * 60
private const val LOGIN_ATTEMPT_LIMIT = 5
private const val REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val refreshToken: String)
@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(val token: String, val newPassword: String)

private fun resetTokenHash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
private fun newRefreshToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })

@Serializable
data class UserProfileResponse(
    val email: String,
    val role: String,
    val createdAt: String
)

fun Application.configureAuthRoutes() {
    routing {
        post("/api/auth/forgot-password") {
            val body = runCatching { call.receive<ForgotPasswordRequest>() }.getOrNull()
            if (body == null || !InputValidators.isValidEmail(body.email.trim())) {
                call.respond(mapOf("status" to "reset_email_queued"))
                return@post
            }
            val email = body.email.lowercase().trim()
            val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
            if (user != null && AppConfig.passwordResetUrl.isNotBlank()) {
                val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
                transaction {
                    PasswordResetTokens.deleteWhere { PasswordResetTokens.userId eq user[Users.id] }
                    PasswordResetTokens.insert {
                        it[userId] = user[Users.id]
                        it[tokenHash] = resetTokenHash(token)
                        it[expiresAt] = LocalDateTime.now().plusMinutes(30)
                    }
                }
                ResendClient.sendPasswordReset(email, "${AppConfig.passwordResetUrl.trimEnd('/')}/$token")
            }
            call.respond(mapOf("status" to "reset_email_queued"))
        }

        post("/api/auth/reset-password") {
            val body = runCatching { call.receive<ResetPasswordRequest>() }.getOrNull()
            if (body == null || body.token.isBlank() || body.newPassword.length < 8) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A valid reset token and password of at least 8 characters are required")
                return@post
            }
            val now = LocalDateTime.now()
            val tokenRow = transaction {
                PasswordResetTokens.selectAll().where {
                    (PasswordResetTokens.tokenHash eq resetTokenHash(body.token)) and PasswordResetTokens.usedAt.isNull()
                }.singleOrNull()
            }
            if (tokenRow == null || tokenRow[PasswordResetTokens.expiresAt].isBefore(now)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_reset_token", "This password reset link is invalid or expired")
                return@post
            }
            val hash = BCrypt.hashpw(body.newPassword, BCrypt.gensalt(12))
            transaction {
                Users.update({ Users.id eq tokenRow[PasswordResetTokens.userId] }) { it[passwordHash] = hash }
                PasswordResetTokens.update({ PasswordResetTokens.id eq tokenRow[PasswordResetTokens.id] }) { it[usedAt] = now }
            }
            call.respond(mapOf("status" to "password_reset"))
        }

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

            val role = user[Users.role]
            val token = JwtConfig.createToken(email, role)
            val refreshToken = newRefreshToken()
            RedisService.set("auth:refresh:${resetTokenHash(refreshToken)}", "$email|$role", REFRESH_TTL_SECONDS)
            runCatching { RedisService.delete(emailKey) }
            logger.info("Successful login: $email (account created ${user[Users.createdAt]})")
            call.respond(LoginResponse(token, refreshToken))
        }

        post("/api/auth/refresh") {
            val body = runCatching { call.receive<RefreshRequest>() }.getOrNull()
            if (body == null || body.refreshToken.isBlank()) { call.respondError(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Refresh token is invalid"); return@post }
            val key = "auth:refresh:${resetTokenHash(body.refreshToken)}"
            val value = RedisService.get(key)
            if (value.isNullOrBlank()) { call.respondError(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Refresh token is invalid or expired"); return@post }
            val parts = value.split('|', limit = 2)
            val email = parts[0]; val role = parts.getOrElse(1) { "admin" }
            val nextRefreshToken = newRefreshToken()
            RedisService.delete(key)
            RedisService.set("auth:refresh:${resetTokenHash(nextRefreshToken)}", "$email|$role", REFRESH_TTL_SECONDS)
            call.respond(LoginResponse(JwtConfig.createToken(email, role), nextRefreshToken))
        }

        post("/api/auth/revoke-refresh") {
            val body = runCatching { call.receive<RefreshRequest>() }.getOrNull()
            if (body?.refreshToken?.isNotBlank() == true) RedisService.delete("auth:refresh:${resetTokenHash(body.refreshToken)}")
            call.respond(mapOf("status" to "refresh_revoked"))
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
