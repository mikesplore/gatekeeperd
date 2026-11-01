package com.gatekeeper.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.util.Base64
import java.util.UUID

object AuthSupport {
    const val LOGIN_WINDOW_SECONDS = 15 * 60
    const val LOGIN_ATTEMPT_LIMIT = 10
    const val REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60
    const val TOTP_PENDING_TTL_SECONDS = 10 * 60
    const val TWO_FACTOR_FAILURE_LIMIT = 5
    const val TWO_FACTOR_LOCK_SECONDS = 15 * 60

    fun resetTokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    fun gravatarUrl(email: String): String = "https://www.gravatar.com/avatar/${
        MessageDigest.getInstance("MD5").digest(email.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }?d=404&s=96"

    fun newRefreshToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })

    fun pendingTotpKey(userId: UUID) = "auth:2fa:pending:$userId"
    fun loginChallengeKey(token: String) = "auth:2fa:challenge:${resetTokenHash(token)}"
    fun recoveryCode(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(9).also { SecureRandom().nextBytes(it) }).take(12).uppercase()

    fun auditContext(call: ApplicationCall): String =
        "ip=${call.request.local.remoteHost} requestId=${call.request.headers["X-Request-ID"] ?: "unknown"}"

    fun isUniqueConstraintViolation(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == "23505" }
}
