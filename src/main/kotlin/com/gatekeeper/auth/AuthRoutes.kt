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

private val logger = LoggerFactory.getLogger("com.gatekeeper.auth.AuthRoutes")
fun Application.configureAuthRoutes() {
    val authUsers = get<AuthUserRepository>()
    val auditRepository = get<AuditRepositoryPort>()
    val rbac = get<RbacApplicationService>()
    val settings = get<AppSettings>()
    val resendClient = get<ResendClient>()
    routing {










        authenticate("auth-jwt", optional = true) {
            post("/api/auth/2fa/verify") {
                val body = runCatching { call.receive<VerifyTwoFactorRequest>() }.getOrNull()
                if (body == null || body.challengeToken.isBlank()) {
                    call.respondError(
                        HttpStatusCode.Unauthorized,
                        "invalid_two_factor_challenge",
                        "The two-factor challenge is invalid"
                    ); return@post
                }
                val key = AuthSupport.loginChallengeKey(body.challengeToken);
                val value = RedisService.get(key)
                if (value.isNullOrBlank()) {
                    call.respondError(
                        HttpStatusCode.Unauthorized,
                        "invalid_two_factor_challenge",
                        "The two-factor challenge is invalid or expired"
                    ); return@post
                }
                val parts = value.split('|', limit = 2);
                val email = parts[0];
                val role = parts.getOrElse(1) { "admin" }
                if (RedisService.get("auth:2fa:lock:$email") != null) {
                    call.respondError(
                        HttpStatusCode.TooManyRequests,
                        "two_factor_locked",
                        "Too many failed attempts. Try again later."
                    ); return@post
                }
                val user = authUsers.findByEmail(email)
                if (user == null) {
                    RedisService.delete(key); call.respondError(
                        HttpStatusCode.Unauthorized,
                        "invalid_two_factor_challenge",
                        "The two-factor challenge is invalid"
                    ); return@post
                }
                val secret = user.totpSecret?.let { SecretValueCipher.decrypt(it) }
                var recoveryUsed = false;
                var valid = secret != null && Totp.verify(secret, body.code)
                if (!valid) {
                    val matched = user.recoveryCodes.indexOfFirst { RecoveryCodeHasher.matches(body.code, it) }
                    if (matched >= 0) {
                        recoveryUsed = true; valid = true
                        authUsers.replaceRecoveryCodes(user.id, user.recoveryCodes.filterIndexed { index, _ -> index != matched })
                    }
                }
                if (!valid) {
                    val attempts = RedisService.incrementWithExpiry(
                        "auth:2fa:fail:$email",
                        AuthSupport.TWO_FACTOR_LOCK_SECONDS
                    ); if (attempts >= AuthSupport.TWO_FACTOR_FAILURE_LIMIT) RedisService.setRequired(
                        "auth:2fa:lock:$email",
                        "1",
                        AuthSupport.TWO_FACTOR_LOCK_SECONDS
                    ); auditRepository.write(
                        null,
                        "2FA Verification Failed",
                        email,
                        "Invalid authenticator or recovery code ${AuthSupport.auditContext(call)}"
                    ); call.respondError(
                        HttpStatusCode.Unauthorized,
                        "invalid_two_factor_code",
                        "The verification code is invalid"
                    ); return@post
                }
                RedisService.delete(key)
                if (recoveryUsed) auditRepository.write(
                    null,
                    "2FA Recovery Code Used",
                    email,
                    "A recovery code was used ${AuthSupport.auditContext(call)}"
                )
                val token = JwtConfig.createToken(settings.auth, email, role);
                val refreshToken = AuthSupport.newRefreshToken()
                RedisService.setRequired(
                    "auth:refresh:${AuthSupport.resetTokenHash(refreshToken)}",
                    "$email|$role",
                    AuthSupport.REFRESH_TTL_SECONDS
                )
                call.respond(LoginResponse(token, refreshToken))
            }

            post("/api/auth/2fa/disable") {
                val principal = call.principal<JWTPrincipal>();
                val body = runCatching { call.receive<DisableTwoFactorRequest>() }.getOrNull()
                if (principal == null || body == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Password and two-factor code are required"
                    ); return@post
                }
                val email = principal.payload.subject.lowercase().trim();
                val user = authUsers.findByEmail(email)
                if (user == null || !BCrypt.checkpw(body.currentPassword, user.passwordHash)) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_credentials",
                        "Current password is incorrect"
                    ); return@post
                }
                val secret = user.totpSecret?.let { SecretValueCipher.decrypt(it) };
                var valid = secret != null && Totp.verify(secret, body.code)
                if (!valid) valid = user.recoveryCodes.any { RecoveryCodeHasher.matches(body.code, it) }
                if (!valid) {
                    auditRepository.write(
                        null,
                        "2FA Disable Failed",
                        email,
                        "Invalid authenticator or recovery code"
                    ); call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_two_factor_code",
                        "The verification code is invalid"
                    ); return@post
                }
                authUsers.updateTwoFactor(user.id, null, false, emptyList())
                auditRepository.write(
                    null,
                    "2FA Disabled",
                    email,
                    "Authenticator-based two-factor authentication disabled"
                )
                call.respond(mapOf("status" to "two_factor_disabled"))
            }

            post("/api/auth/2fa/recovery-codes/regenerate") {
                val principal = call.principal<JWTPrincipal>();
                val body = runCatching { call.receive<DisableTwoFactorRequest>() }.getOrNull()
                if (principal == null || body == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Password and two-factor code are required"
                    ); return@post
                }
                val email = principal.payload.subject.lowercase().trim();
                val user = authUsers.findByEmail(email)
                if (user == null || !BCrypt.checkpw(body.currentPassword, user.passwordHash)) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_credentials",
                        "Current password is incorrect"
                    ); return@post
                }
                val secret = user.totpSecret?.let { SecretValueCipher.decrypt(it) };
                var valid = secret != null && Totp.verify(secret, body.code)
                if (!valid) valid = user.recoveryCodes.any { RecoveryCodeHasher.matches(body.code, it) }
                if (!valid) {
                    auditRepository.write(
                        null,
                        "2FA Recovery Regeneration Failed",
                        email,
                        "Invalid authenticator or recovery code ${AuthSupport.auditContext(call)}"
                    ); call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_two_factor_code",
                        "The verification code is invalid"
                    ); return@post
                }
                val codes = List(8) { AuthSupport.recoveryCode() };
                val hashes = codes.map(RecoveryCodeHasher::hash)
                authUsers.replaceRecoveryCodes(user.id, hashes)
                auditRepository.write(
                    null,
                    "2FA Recovery Codes Regenerated",
                    email,
                    "Recovery codes replaced ${AuthSupport.auditContext(call)}"
                )
                call.respond(mapOf("recoveryCodes" to codes))
            }

            post("/api/auth/2fa/setup") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null || !SecretValueCipher.isConfigured()) {
                    call.respondError(
                        HttpStatusCode.ServiceUnavailable,
                        "two_factor_unavailable",
                        "Two-factor setup is temporarily unavailable"
                    ); return@post
                }
                val email = principal.payload.subject.lowercase().trim()
                val user = authUsers.findByEmail(email)
                if (user == null) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found"); return@post
                }
                if (RedisService.get(AuthSupport.pendingTotpKey(user.id)) != null) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "two_factor_setup_pending",
                        "A two-factor setup is already pending"
                    ); return@post
                }
                if (user.totpEnabled) {
                    call.respondError(
                        HttpStatusCode.Conflict,
                        "two_factor_enabled",
                        "Two-factor authentication is already enabled"
                    ); return@post
                }
                val secret = Totp.newSecret();
                val codes = List(8) { AuthSupport.recoveryCode() }
                val pending = SecretValueCipher.encrypt(secret) + "|" + codes.joinToString(
                    ",",
                    transform = RecoveryCodeHasher::hash
                )
                RedisService.set(AuthSupport.pendingTotpKey(user.id), pending, AuthSupport.TOTP_PENDING_TTL_SECONDS)
                val uri = "otpauth://totp/Gatekeeperd:${
                    java.net.URLEncoder.encode(
                        email,
                        Charsets.UTF_8
                    )
                }?secret=$secret&issuer=Gatekeeperd&algorithm=SHA1&digits=6&period=30"
                call.respond(TotpSetupResponse(secret, uri, codes))
            }

            post("/api/auth/2fa/enable") {
                val principal = call.principal<JWTPrincipal>();
                val body = runCatching { call.receive<TotpCodeRequest>() }.getOrNull()
                if (principal == null || body == null) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "A six-digit code is required"
                    ); return@post
                }
                val email = principal.payload.subject.lowercase().trim();
                val user = authUsers.findByEmail(email)
                if (user == null) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found"); return@post
                }
                val pending = RedisService.get(AuthSupport.pendingTotpKey(user.id));
                val separator = pending?.indexOf('|') ?: -1
                if (pending.isNullOrBlank() || separator < 1) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "two_factor_setup_expired",
                        "Start two-factor setup again"
                    ); return@post
                }
                val secret = SecretValueCipher.decrypt(pending.substring(0, separator))
                if (!Totp.verify(secret, body.code)) {
                    auditRepository.write(
                        null,
                        "2FA Enable Failed",
                        email,
                        "Invalid verification code"
                    ); call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_two_factor_code",
                        "The verification code is invalid"
                    ); return@post
                }
                val hashes = pending.substring(separator + 1).split(',').filter(String::isNotBlank)
                authUsers.updateTwoFactor(user.id, SecretValueCipher.encrypt(secret), true, hashes)
                RedisService.delete(AuthSupport.pendingTotpKey(user.id)); auditRepository.write(
                null,
                "2FA Enabled",
                email,
                "Authenticator-based two-factor authentication enabled"
            )
                call.respond(mapOf("status" to "two_factor_enabled"))
            }

            post("/api/auth/password") {
                val principal = call.principal<JWTPrincipal>()
                val body = try {
                    call.receive<ChangePasswordRequest>()
                } catch (e: Exception) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Current and new passwords are required"
                    )
                    return@post
                }
                if (principal == null || body.currentPassword.isBlank() || body.newPassword.length < 8) {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "New password must be at least 8 characters"
                    )
                    return@post
                }
                val email = principal.payload.subject.lowercase().trim()
                val user = authUsers.findByEmail(email)
                if (user == null || !BCrypt.checkpw(body.currentPassword, user.passwordHash)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Current password is incorrect")
                    return@post
                }
                val hash = BCrypt.hashpw(body.newPassword, BCrypt.gensalt(12))
                authUsers.updatePassword(email, hash)
                auditRepository.write(null, "Password Changed", email, "Account password changed")
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
                try {
                    RedisService.setRequired("auth:revoked:$jti", "1", ttlSeconds.toInt())
                } catch (_: Exception) {
                    logger.warn("Unable to persist logout token revocation")
                    call.respondError(
                        HttpStatusCode.ServiceUnavailable,
                        "revocation_unavailable",
                        "Logout is temporarily unavailable"
                    )
                    return@post
                }
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
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_request",
                        "Display name exceeds the maximum length"
                    )
                    return@patch
                }
                val email = principal.payload.subject.lowercase().trim()
                authUsers.updateDisplayName(email, body.displayName?.trim().orEmpty())
                auditRepository.write(null, "Profile Updated", email, "Display name changed")
                call.respond(mapOf("status" to "profile_updated"))
            }

            get("/api/auth/me") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
                    return@get
                }
                val email = principal.payload.subject

                val user = authUsers.findByEmail(email.lowercase().trim())
                if (user == null) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found")
                    return@get
                }

                call.respond(
                    UserProfileResponse(
                        email = user.email,
                        role = user.role,
                        displayName = user.displayName,
                        avatarUrl = AuthSupport.gravatarUrl(user.email),
                        totpEnabled = user.totpEnabled,
                        createdAt = user.createdAt.toString()
                    )
                )
            }








        }
    }
    routing {
        publicAuthRoutes(authUsers, settings, resendClient)
        adminUserRoutes(authUsers, auditRepository, rbac)
    }
}
