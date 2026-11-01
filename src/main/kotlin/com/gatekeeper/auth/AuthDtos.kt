package com.gatekeeper.auth

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import java.util.*

// Extracted route DTOs
@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class CreateAdminUserRequest(val email: String, val password: String, val role: String = "admin")

@Serializable
data class UpdateAdminUserRequest(val email: String? = null, val role: String? = null, val active: Boolean? = null)

@Serializable
data class AdminUserResponse(
    val id: String,
    val email: String,
    val role: String,
    val twoFactorEnabled: Boolean,
    val createdAt: String,
    val active: Boolean = true
)

@Serializable
data class AdminUsersPageResponse(
    val users: List<AdminUserResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
data class LoginResponse(
    val token: String = "",
    val refreshToken: String = "",
    val requiresTwoFactor: Boolean = false,
    val challengeToken: String? = null
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class UpdateProfileRequest(val displayName: String? = null)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class UserProfileResponse(
    val email: String,
    val role: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val totpEnabled: Boolean = false,
    val createdAt: String,
    val permissions: Set<String> = emptySet()
)

@Serializable
data class TotpSetupResponse(val secret: String, val otpauthUri: String, val recoveryCodes: List<String>)

@Serializable
data class TotpCodeRequest(val code: String)

@Serializable
data class VerifyTwoFactorRequest(val challengeToken: String, val code: String)

@Serializable
data class DisableTwoFactorRequest(val currentPassword: String, val code: String)
