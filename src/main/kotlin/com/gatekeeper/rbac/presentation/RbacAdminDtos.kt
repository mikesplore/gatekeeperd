package com.gatekeeper.rbac.presentation

import kotlinx.serialization.Serializable

@Serializable
data class CreateRoleRequest(
    val key: String,
    val displayName: String,
    val description: String? = null,
    val permissions: Set<String> = emptySet()
)

@Serializable
data class UpdateRoleRequest(
    val displayName: String,
    val description: String?,
    val active: Boolean,
    val permissions: Set<String>
)

@Serializable
data class PermissionResponse(
    val code: String,
    val category: String,
    val action: String,
    val description: String
)

@Serializable
data class RoleResponse(
    val id: String,
    val key: String,
    val displayName: String,
    val description: String?,
    val superAdmin: Boolean,
    val active: Boolean,
    val permissions: Set<String>,
    val createdAt: String,
    val updatedAt: String
)
