package com.gatekeeper.auth

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.Users
import com.gatekeeper.db.tables.PasswordResetTokens
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.integrations.ResendClient
import com.gatekeeper.config.AppConfig
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val logger = LoggerFactory.getLogger("com.gatekeeper.auth.AuthRoutes")
private const val LOGIN_WINDOW_SECONDS = 15 * 60
private const val LOGIN_ATTEMPT_LIMIT = 5
private const val REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String = "", val refreshToken: String = "", val requiresTwoFactor: Boolean = false, val challengeToken: String? = null)
@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class UpdateProfileRequest(val displayName: String? = null)
@Serializable data class ForgotPasswordRequest(val email: String)
@Serializable data class ResetPasswordRequest(val token: String, val newPassword: String)

private fun resetTokenHash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
private fun gravatarUrl(email: String): String = "https://www.gravatar.com/avatar/${MessageDigest.getInstance("MD5").digest(email.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }}?d=404&s=96"
private fun newRefreshToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })

@Serializable
data class UserProfileResponse(
    val email: String,
    val role: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val totpEnabled: Boolean = false,
    val createdAt: String
)

@Serializable data class TotpSetupResponse(val secret: String, val otpauthUri: String, val recoveryCodes: List<String>)
@Serializable data class TotpCodeRequest(val code: String)
@Serializable data class VerifyTwoFactorRequest(val challengeToken: String, val code: String)
@Serializable data class DisableTwoFactorRequest(val currentPassword: String, val code: String)

private const val TOTP_PENDING_TTL_SECONDS = 10 * 60
private fun pendingTotpKey(userId: UUID) = "auth:2fa:pending:$userId"
private fun loginChallengeKey(token: String) = "auth:2fa:challenge:${resetTokenHash(token)}"
private fun recoveryCode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also { SecureRandom().nextBytes(it) }).take(12).uppercase()
private fun auditContext(call: ApplicationCall): String = "ip=${call.request.local.remoteHost} requestId=${call.request.headers["X-Request-ID"] ?: "unknown"}"
private const val TWO_FACTOR_FAILURE_LIMIT = 5
private const val TWO_FACTOR_LOCK_SECONDS = 15 * 60

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
                val resetLink = "${AppConfig.passwordResetUrl.trimEnd('/')}?token=$token"
                call.application.launch(Dispatchers.IO) {
                    ResendClient.sendPasswordReset(email, resetLink)
                }
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

            if (user[Users.totpEnabled]) {
                val challenge = newRefreshToken()
                RedisService.set(loginChallengeKey(challenge), "$email|${user[Users.role]}", 5 * 60)
                call.respond(LoginResponse(requiresTwoFactor = true, challengeToken = challenge))
                return@post
            }

            val role = user[Users.role]
            val token = JwtConfig.createToken(email, role)
            val refreshToken = newRefreshToken()
            val refreshStored = runCatching {
                RedisService.set("auth:refresh:${resetTokenHash(refreshToken)}", "$email|$role", REFRESH_TTL_SECONDS)
            }.isSuccess
            if (!refreshStored) {
                logger.error("Login refused because refresh-token storage is unavailable")
                call.respondError(HttpStatusCode.ServiceUnavailable, "redis_unavailable", "Authentication storage is temporarily unavailable")
                return@post
            }
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

        authenticate("auth-jwt", optional = true) {
            post("/api/auth/2fa/verify") {
                val body = runCatching { call.receive<VerifyTwoFactorRequest>() }.getOrNull()
                if (body == null || body.challengeToken.isBlank()) { call.respondError(HttpStatusCode.Unauthorized, "invalid_two_factor_challenge", "The two-factor challenge is invalid"); return@post }
                val key = loginChallengeKey(body.challengeToken); val value = RedisService.get(key)
                if (value.isNullOrBlank()) { call.respondError(HttpStatusCode.Unauthorized, "invalid_two_factor_challenge", "The two-factor challenge is invalid or expired"); return@post }
                val parts = value.split('|', limit = 2); val email = parts[0]; val role = parts.getOrElse(1) { "admin" }
                if (RedisService.get("auth:2fa:lock:$email") != null) { call.respondError(HttpStatusCode.TooManyRequests, "two_factor_locked", "Too many failed attempts. Try again later."); return@post }
                val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
                if (user == null) { RedisService.delete(key); call.respondError(HttpStatusCode.Unauthorized, "invalid_two_factor_challenge", "The two-factor challenge is invalid"); return@post }
                val secret = user[Users.totpSecret]?.let { SecretValueCipher.decrypt(it) }
                var recoveryUsed = false; var valid = secret != null && Totp.verify(secret, body.code)
                if (!valid) {
                    val matched = user[Users.recoveryCodes].indexOfFirst { RecoveryCodeHasher.matches(body.code, it) }
                    if (matched >= 0) { recoveryUsed = true; valid = true; transaction { Users.update({ Users.id eq user[Users.id] }) { it[Users.recoveryCodes] = user[Users.recoveryCodes].filterIndexed { index, _ -> index != matched } } } }
                }
                if (!valid) { val attempts = RedisService.incrementWithExpiry("auth:2fa:fail:$email", TWO_FACTOR_LOCK_SECONDS) ?: 0; if (attempts >= TWO_FACTOR_FAILURE_LIMIT) RedisService.set("auth:2fa:lock:$email", "1", TWO_FACTOR_LOCK_SECONDS); AuditRepository.write(null, "2FA Verification Failed", email, "Invalid authenticator or recovery code ${auditContext(call)}"); call.respondError(HttpStatusCode.Unauthorized, "invalid_two_factor_code", "The verification code is invalid"); return@post }
                RedisService.delete(key)
                if (recoveryUsed) AuditRepository.write(null, "2FA Recovery Code Used", email, "A recovery code was used ${auditContext(call)}")
                val token = JwtConfig.createToken(email, role); val refreshToken = newRefreshToken()
                RedisService.set("auth:refresh:${resetTokenHash(refreshToken)}", "$email|$role", REFRESH_TTL_SECONDS)
                call.respond(LoginResponse(token, refreshToken))
            }

            post("/api/auth/2fa/disable") {
                val principal = call.principal<JWTPrincipal>(); val body = runCatching { call.receive<DisableTwoFactorRequest>() }.getOrNull()
                if (principal == null || body == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Password and two-factor code are required"); return@post }
                val email = principal.payload.subject.lowercase().trim(); val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
                if (user == null || !BCrypt.checkpw(body.currentPassword, user[Users.passwordHash])) { call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Current password is incorrect"); return@post }
                val secret = user[Users.totpSecret]?.let { SecretValueCipher.decrypt(it) }; var valid = secret != null && Totp.verify(secret, body.code)
                if (!valid) valid = user[Users.recoveryCodes].any { RecoveryCodeHasher.matches(body.code, it) }
                if (!valid) { AuditRepository.write(null, "2FA Disable Failed", email, "Invalid authenticator or recovery code"); call.respondError(HttpStatusCode.BadRequest, "invalid_two_factor_code", "The verification code is invalid"); return@post }
                transaction { Users.update({ Users.id eq user[Users.id] }) { it[Users.totpSecret] = null; it[Users.totpEnabled] = false; it[Users.recoveryCodes] = emptyList() } }
                AuditRepository.write(null, "2FA Disabled", email, "Authenticator-based two-factor authentication disabled")
                call.respond(mapOf("status" to "two_factor_disabled"))
            }

            post("/api/auth/2fa/recovery-codes/regenerate") {
                val principal = call.principal<JWTPrincipal>(); val body = runCatching { call.receive<DisableTwoFactorRequest>() }.getOrNull()
                if (principal == null || body == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Password and two-factor code are required"); return@post }
                val email = principal.payload.subject.lowercase().trim(); val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
                if (user == null || !BCrypt.checkpw(body.currentPassword, user[Users.passwordHash])) { call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Current password is incorrect"); return@post }
                val secret = user[Users.totpSecret]?.let { SecretValueCipher.decrypt(it) }; var valid = secret != null && Totp.verify(secret, body.code)
                if (!valid) valid = user[Users.recoveryCodes].any { RecoveryCodeHasher.matches(body.code, it) }
                if (!valid) { AuditRepository.write(null, "2FA Recovery Regeneration Failed", email, "Invalid authenticator or recovery code ${auditContext(call)}"); call.respondError(HttpStatusCode.BadRequest, "invalid_two_factor_code", "The verification code is invalid"); return@post }
                val codes = List(8) { recoveryCode() }; val hashes = codes.map(RecoveryCodeHasher::hash)
                transaction { Users.update({ Users.id eq user[Users.id] }) { it[Users.recoveryCodes] = hashes } }
                AuditRepository.write(null, "2FA Recovery Codes Regenerated", email, "Recovery codes replaced ${auditContext(call)}")
                call.respond(mapOf("recoveryCodes" to codes))
            }

            post("/api/auth/2fa/setup") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null || !SecretValueCipher.isConfigured()) { call.respondError(HttpStatusCode.ServiceUnavailable, "two_factor_unavailable", "Two-factor setup is temporarily unavailable"); return@post }
                val email = principal.payload.subject.lowercase().trim()
                val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
                if (user == null) { call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found"); return@post }
                if (RedisService.get(pendingTotpKey(user[Users.id])) != null) { call.respondError(HttpStatusCode.Conflict, "two_factor_setup_pending", "A two-factor setup is already pending"); return@post }
                if (user[Users.totpEnabled]) { call.respondError(HttpStatusCode.Conflict, "two_factor_enabled", "Two-factor authentication is already enabled"); return@post }
                val secret = Totp.newSecret(); val codes = List(8) { recoveryCode() }
                val pending = SecretValueCipher.encrypt(secret) + "|" + codes.joinToString(",", transform = RecoveryCodeHasher::hash)
                RedisService.set(pendingTotpKey(user[Users.id]), pending, TOTP_PENDING_TTL_SECONDS)
                val uri = "otpauth://totp/Gatekeeperd:${java.net.URLEncoder.encode(email, Charsets.UTF_8)}?secret=$secret&issuer=Gatekeeperd&algorithm=SHA1&digits=6&period=30"
                call.respond(TotpSetupResponse(secret, uri, codes))
            }

            post("/api/auth/2fa/enable") {
                val principal = call.principal<JWTPrincipal>(); val body = runCatching { call.receive<TotpCodeRequest>() }.getOrNull()
                if (principal == null || body == null) { call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A six-digit code is required"); return@post }
                val email = principal.payload.subject.lowercase().trim(); val user = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
                if (user == null) { call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found"); return@post }
                val pending = RedisService.get(pendingTotpKey(user[Users.id])); val separator = pending?.indexOf('|') ?: -1
                if (pending.isNullOrBlank() || separator < 1) { call.respondError(HttpStatusCode.BadRequest, "two_factor_setup_expired", "Start two-factor setup again"); return@post }
                val secret = SecretValueCipher.decrypt(pending.substring(0, separator))
                if (!Totp.verify(secret, body.code)) { AuditRepository.write(null, "2FA Enable Failed", email, "Invalid verification code"); call.respondError(HttpStatusCode.BadRequest, "invalid_two_factor_code", "The verification code is invalid"); return@post }
                val hashes = pending.substring(separator + 1).split(',').filter(String::isNotBlank)
                transaction { Users.update({ Users.id eq user[Users.id] }) { it[Users.totpSecret] = SecretValueCipher.encrypt(secret); it[Users.totpEnabled] = true; it[Users.recoveryCodes] = hashes } }
                RedisService.delete(pendingTotpKey(user[Users.id])); AuditRepository.write(null, "2FA Enabled", email, "Authenticator-based two-factor authentication enabled")
                call.respond(mapOf("status" to "two_factor_enabled"))
            }

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
                AuditRepository.write(null, "Password Changed", email, "Account password changed")
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

            patch("/api/auth/me") {
                val principal = call.principal<JWTPrincipal>()
                val body = runCatching { call.receive<UpdateProfileRequest>() }.getOrNull()
                if (principal == null || body == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Profile data is invalid")
                    return@patch
                }
                if ((body.displayName?.trim()?.length ?: 0) > 80) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Display name exceeds the maximum length")
                    return@patch
                }
                val email = principal.payload.subject.lowercase().trim()
                transaction {
                    Users.update({ Users.email eq email }) {
                        it[Users.displayName] = body.displayName?.trim()?.takeIf(String::isNotBlank)
                    }
                }
                AuditRepository.write(null, "Profile Updated", email, "Display name changed")
                call.respond(mapOf("status" to "profile_updated"))
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
                        displayName = user[Users.displayName],
                        avatarUrl = gravatarUrl(user[Users.email]),
                        totpEnabled = user[Users.totpEnabled],
                        createdAt = user[Users.createdAt].toString()
                    )
                )
            }
        }
    }
}
