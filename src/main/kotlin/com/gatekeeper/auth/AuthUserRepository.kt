package com.gatekeeper.auth

import java.time.LocalDateTime
import java.util.UUID

data class AuthUser(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val avatarUrl: String?,
    val totpSecret: String?,
    val totpEnabled: Boolean,
    val recoveryCodes: List<String>,
    val passwordHash: String,
    val role: String,
    val active: Boolean,
    val createdAt: LocalDateTime
)

data class PasswordResetToken(
    val id: UUID,
    val userId: UUID,
    val expiresAt: LocalDateTime,
    val usedAt: LocalDateTime?
)

interface AuthUserRepository {
    fun findByEmail(email: String): AuthUser?
    fun findById(id: UUID): AuthUser?
    fun createPasswordResetToken(userId: UUID, tokenHash: String, expiresAt: LocalDateTime)
    fun findPasswordResetToken(tokenHash: String): PasswordResetToken?
    fun completePasswordReset(tokenId: UUID, userId: UUID, hash: String, usedAt: LocalDateTime)
    fun replaceRecoveryCodes(userId: UUID, codes: List<String>)
    fun updateTwoFactor(userId: UUID, secret: String?, enabled: Boolean, recoveryCodes: List<String>)
    fun updateDisplayName(email: String, displayName: String)
    fun updatePassword(email: String, hash: String)
    fun listUsers(query: String?, limit: Int, offset: Long): Pair<List<AuthUser>, Long>
    fun createAdmin(email: String, passwordHash: String, role: String): AuthUser
    fun updateAdmin(id: UUID, email: String?, role: String?, active: Boolean?): AuthUser?
    fun deleteAdmin(id: UUID): Boolean
    fun countAdmins(): Long
}
