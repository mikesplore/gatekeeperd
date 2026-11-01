package com.gatekeeper.rbac.domain

interface RbacRepository {
    fun permissionDefinitions(): List<PermissionDefinition>
    fun listRoles(): List<AccessRole>
    fun findRole(key: String): AccessRole?
    fun createRole(draft: RoleDraft): AccessRole
    fun updateRole(key: String, displayName: String, description: String?, active: Boolean, permissions: Set<String>): AccessRole?
    fun deleteRole(key: String): Boolean
    fun roleExists(key: String): Boolean
    fun hasPermission(email: String, permission: String): Boolean
    fun permissionsForUser(email: String): Set<String>
    fun isSuperAdmin(email: String): Boolean
}
