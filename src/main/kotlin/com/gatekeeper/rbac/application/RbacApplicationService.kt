package com.gatekeeper.rbac.application

import com.gatekeeper.api.HttpApiException
import com.gatekeeper.rbac.domain.AccessRole
import com.gatekeeper.rbac.domain.PermissionDefinition
import com.gatekeeper.rbac.domain.RbacCatalog
import com.gatekeeper.rbac.domain.RbacRepository
import com.gatekeeper.rbac.domain.RoleDraft
import io.ktor.http.HttpStatusCode

class RbacApplicationService(private val repository: RbacRepository) {
    fun permissions(): List<PermissionDefinition> = repository.permissionDefinitions()

    fun roles(): List<AccessRole> = repository.listRoles()

    fun role(key: String): AccessRole = repository.findRole(key)
        ?: throw HttpApiException(HttpStatusCode.NotFound, "role_not_found", "Role not found")

    fun roleExists(key: String): Boolean = repository.roleExists(key)

    fun createRole(actor: String, draft: RoleDraft): AccessRole {
        val normalized = normalizeDraft(draft)
        validatePermissionGrant(actor, normalized.permissions)
        return try {
            repository.createRole(normalized)
        } catch (error: Exception) {
            if (repository.roleExists(normalized.key)) {
                throw HttpApiException(HttpStatusCode.Conflict, "role_exists", "A role with this key already exists")
            }
            throw error
        }
    }

    fun updateRole(
        actor: String,
        key: String,
        displayName: String,
        description: String?,
        active: Boolean,
        permissionCodes: Set<String>
    ): AccessRole {
        val existing = role(key)
        if (existing.superAdmin) {
            throw HttpApiException(HttpStatusCode.Conflict, "system_role_immutable", "The built-in administrator role cannot be changed")
        }
        val normalized = normalizeDraft(RoleDraft(key, displayName, description, permissionCodes))
        validatePermissionGrant(actor, normalized.permissions)
        return repository.updateRole(key, normalized.displayName, normalized.description, active, normalized.permissions)
            ?: throw HttpApiException(HttpStatusCode.NotFound, "role_not_found", "Role not found")
    }

    fun deleteRole(key: String) {
        val existing = role(key)
        if (existing.superAdmin) {
            throw HttpApiException(HttpStatusCode.Conflict, "system_role_immutable", "The built-in administrator role cannot be deleted")
        }
        if (!repository.deleteRole(key)) {
            throw HttpApiException(HttpStatusCode.Conflict, "role_in_use", "A role assigned to users cannot be deleted")
        }
    }

    fun mayAssignRole(actor: String, roleKey: String): Boolean {
        val target = repository.findRole(roleKey) ?: return false
        if (!target.active) return false
        if (repository.isSuperAdmin(actor)) return true
        if (target.superAdmin) return false
        return repository.permissionsForUser(actor).containsAll(target.permissions)
    }

    fun authorize(email: String, permission: String): Boolean =
        permission in RbacCatalog.byCode && repository.hasPermission(email, permission)

    fun isSuperAdmin(email: String): Boolean = repository.isSuperAdmin(email)

    private fun normalizeDraft(draft: RoleDraft): RoleDraft {
        val key = draft.key.trim().lowercase()
        if (!key.matches(ROLE_KEY)) {
            throw HttpApiException(HttpStatusCode.BadRequest, "invalid_role_key", "Role key must start with a letter and contain only lowercase letters, digits, hyphens, or underscores")
        }
        if (draft.displayName.isBlank() || draft.displayName.trim().length > 100) {
            throw HttpApiException(HttpStatusCode.BadRequest, "invalid_role_name", "Display name is required and must be at most 100 characters")
        }
        val description = draft.description?.trim()?.takeIf(String::isNotEmpty)
        if (description != null && description.length > 500) {
            throw HttpApiException(HttpStatusCode.BadRequest, "invalid_role_description", "Description must be at most 500 characters")
        }
        val unknown = draft.permissions - RbacCatalog.byCode.keys
        if (unknown.isNotEmpty()) {
            throw HttpApiException(HttpStatusCode.BadRequest, "invalid_permissions", "The role contains unknown permissions")
        }
        return RoleDraft(key, draft.displayName.trim(), description, draft.permissions)
    }

    private fun validatePermissionGrant(actor: String, permissions: Set<String>) {
        if (!repository.isSuperAdmin(actor) && !repository.permissionsForUser(actor).containsAll(permissions)) {
            throw HttpApiException(HttpStatusCode.Forbidden, "permission_escalation_denied", "You cannot grant permissions you do not have")
        }
    }

    private companion object {
        val ROLE_KEY = Regex("^[a-z][a-z0-9_-]{1,63}$")
    }
}
