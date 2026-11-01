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

private val logger = LoggerFactory.getLogger("com.gatekeeper.auth.PublicAuthRoutes")

fun Route.publicAuthRoutes(authUsers: AuthUserRepository, settings: AppSettings, resendClient: ResendClient) {
    post("/api/auth/forgot-password") {
        val body = runCatching { call.receive<ForgotPasswordRequest>() }.getOrNull()
        if (body == null || !InputValidators.isValidEmail(body.email.trim())) {
            call.respond(mapOf("status" to "reset_email_queued"))
            return@post
        }
        val email = body.email.lowercase().trim()
        val user = authUsers.findByEmail(email)
        if (user != null && settings.integrations.passwordResetUrl.isNotBlank()) {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
            authUsers.createPasswordResetToken(user.id, AuthSupport.resetTokenHash(token), LocalDateTime.now().plusMinutes(30))
            val resetLink = "${settings.integrations.passwordResetUrl.trimEnd('/')}?token=$token"
            call.application.launch(Dispatchers.IO) {
                resendClient.sendPasswordReset(email, resetLink)
            }
        }
        call.respond(mapOf("status" to "reset_email_queued"))
    }

    post("/api/auth/reset-password") {
        val body = runCatching { call.receive<ResetPasswordRequest>() }.getOrNull()
        if (body == null || body.token.isBlank() || body.newPassword.length < 8) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "A valid reset token and password of at least 8 characters are required"
            )
            return@post
        }
        val now = LocalDateTime.now()
        val tokenRow = authUsers.findPasswordResetToken(AuthSupport.resetTokenHash(body.token))
        if (tokenRow == null || tokenRow.expiresAt.isBefore(now)) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_reset_token",
                "This password reset link is invalid or expired"
            )
            return@post
        }
        val hash = BCrypt.hashpw(body.newPassword, BCrypt.gensalt(12))
        authUsers.completePasswordReset(tokenRow.id, tokenRow.userId, hash, now)
        call.respond(mapOf("status" to "password_reset"))
    }

    post("/api/auth/login") {
        val body = try {
            call.receive<LoginRequest>()
        } catch (_: Exception) {
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
            val emailAttempts = RedisService.incrementWithExpiry(emailKey, AuthSupport.LOGIN_WINDOW_SECONDS)
            val ipAttempts = RedisService.incrementWithExpiry(ipKey, AuthSupport.LOGIN_WINDOW_SECONDS)
            emailAttempts > AuthSupport.LOGIN_ATTEMPT_LIMIT || ipAttempts > AuthSupport.LOGIN_ATTEMPT_LIMIT
        } catch (e: Exception) {
            logger.error("Login refused because Redis-backed rate limiting is unavailable", e)
            call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "redis_unavailable",
                "Authentication storage is temporarily unavailable"
            )
            return@post
        }
        if (rateLimited) {
            call.response.headers.append(HttpHeaders.RetryAfter, AuthSupport.LOGIN_WINDOW_SECONDS.toString())
            call.respondError(
                HttpStatusCode.TooManyRequests,
                "login_rate_limited",
                "Too many login attempts. Try again later."
            )
            return@post
        }

        val user = authUsers.findByEmail(email)

        if (user == null) {
            logger.warn("Login attempt for unknown email: $email")
            call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Invalid email or password")
            return@post
        }

        val passwordHash = user.passwordHash
        if (!BCrypt.checkpw(body.password, passwordHash)) {
            logger.warn("Failed login attempt for: $email")
            call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Invalid email or password")
            return@post
        }

        if (!user.active) {
            call.respondError(HttpStatusCode.Forbidden, "user_suspended", "This account has been suspended")
            return@post
        }

        if (user.totpEnabled) {
            val challenge = AuthSupport.newRefreshToken()
            RedisService.set(AuthSupport.loginChallengeKey(challenge), "$email|${user.role}", 5 * 60)
            call.respond(LoginResponse(requiresTwoFactor = true, challengeToken = challenge))
            return@post
        }

        val role = user.role
        val token = JwtConfig.createToken(settings.auth, email, role)
        val refreshToken = AuthSupport.newRefreshToken()
        val refreshStored = try {
            RedisService.setRequired(
                "auth:refresh:${AuthSupport.resetTokenHash(refreshToken)}",
                "$email|$role",
                AuthSupport.REFRESH_TTL_SECONDS
            )
            true
        } catch (e: Exception) {
            logger.error("Failed to store refresh token in Redis; refusing login", e)
            false
        }
        if (!refreshStored) {
            logger.error("Login refused because refresh-token storage is unavailable")
            call.respondError(
                HttpStatusCode.ServiceUnavailable,
                "redis_unavailable",
                "Authentication storage is temporarily unavailable"
            )
            return@post
        }
        runCatching { RedisService.delete(emailKey) }
        logger.info("Successful login: $email (account created ${user.createdAt})")
        call.respond(LoginResponse(token, refreshToken))
    }

    post("/api/auth/refresh") {
        val body = runCatching { call.receive<RefreshRequest>() }.getOrNull()
        if (body == null || body.refreshToken.isBlank()) {
            call.respondError(
                HttpStatusCode.Unauthorized,
                "invalid_refresh_token",
                "Refresh token is invalid"
            ); return@post
        }
        val key = "auth:refresh:${AuthSupport.resetTokenHash(body.refreshToken)}"
        val value = RedisService.get(key)
        if (value.isNullOrBlank()) {
            call.respondError(
                HttpStatusCode.Unauthorized,
                "invalid_refresh_token",
                "Refresh token is invalid or expired"
            ); return@post
        }
        val parts = value.split('|', limit = 2)
        val email = parts[0];
        val role = parts.getOrElse(1) { "admin" }
        val nextRefreshToken = AuthSupport.newRefreshToken()
        RedisService.delete(key)
        RedisService.set("auth:refresh:${AuthSupport.resetTokenHash(nextRefreshToken)}", "$email|$role", AuthSupport.REFRESH_TTL_SECONDS)
        call.respond(LoginResponse(JwtConfig.createToken(settings.auth, email, role), nextRefreshToken))
    }

    post("/api/auth/revoke-refresh") {
        val body = runCatching { call.receive<RefreshRequest>() }.getOrNull()
        if (body?.refreshToken?.isNotBlank() == true) RedisService.delete("auth:refresh:${AuthSupport.resetTokenHash(body.refreshToken)}")
        call.respond(mapOf("status" to "refresh_revoked"))
    }
}
