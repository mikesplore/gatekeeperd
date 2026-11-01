package com.gatekeeper.rbac.data

import com.gatekeeper.db.tables.AccessRoles
import com.gatekeeper.db.tables.RolePermissions
import com.gatekeeper.db.tables.RbacPermissions
import com.gatekeeper.db.tables.Users
import com.gatekeeper.rbac.domain.AccessRole
import com.gatekeeper.rbac.domain.PermissionDefinition
import com.gatekeeper.rbac.domain.RbacRepository
import com.gatekeeper.rbac.domain.RoleDraft
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class ExposedRbacRepository : RbacRepository {
    override fun permissionDefinitions(): List<PermissionDefinition> = transaction {
        RbacPermissions.selectAll().orderBy(RbacPermissions.category).map {
            PermissionDefinition(it[RbacPermissions.code], it[RbacPermissions.category], it[RbacPermissions.action], it[RbacPermissions.description])
        }
    }

    override fun listRoles(): List<AccessRole> = transaction {
        AccessRoles.selectAll().orderBy(AccessRoles.displayName).map { it.toAccessRole() }
    }

    override fun findRole(key: String): AccessRole? = transaction {
        AccessRoles.selectAll().where { AccessRoles.roleKey eq key }.singleOrNull()?.toAccessRole()
    }

    override fun createRole(draft: RoleDraft): AccessRole = transaction {
        val id = java.util.UUID.randomUUID()
        AccessRoles.insert {
            it[AccessRoles.id] = id
            it[roleKey] = draft.key
            it[displayName] = draft.displayName
            it[description] = draft.description
            it[isSuperAdmin] = false
            it[isActive] = true
        }
        draft.permissions.forEach { code ->
            RolePermissions.insert {
                it[roleId] = id
                it[permissionCode] = code
            }
        }
        AccessRoles.selectAll().where { AccessRoles.id eq id }.single().toAccessRole()
    }

    override fun updateRole(key: String, displayName: String, description: String?, active: Boolean, permissions: Set<String>): AccessRole? = transaction {
        val role = AccessRoles.selectAll().where { AccessRoles.roleKey eq key }.singleOrNull() ?: return@transaction null
        if (role[AccessRoles.isSuperAdmin]) return@transaction role.toAccessRole()
        val id = role[AccessRoles.id]
        AccessRoles.update({ AccessRoles.id eq id }) {
            it[AccessRoles.displayName] = displayName
            it[AccessRoles.description] = description
            it[AccessRoles.isActive] = active
            it[AccessRoles.updatedAt] = LocalDateTime.now()
        }
        RolePermissions.deleteWhere { RolePermissions.roleId eq id }
        permissions.forEach { code ->
            RolePermissions.insert {
                it[roleId] = id
                it[permissionCode] = code
            }
        }
        AccessRoles.selectAll().where { AccessRoles.id eq id }.single().toAccessRole()
    }

    override fun deleteRole(key: String): Boolean = transaction {
        val role = AccessRoles.selectAll().where { AccessRoles.roleKey eq key }.singleOrNull() ?: return@transaction false
        if (role[AccessRoles.isSuperAdmin]) return@transaction false
        val assignedUsers = Users.selectAll().where { Users.role eq key }.count()
        if (assignedUsers > 0L) return@transaction false
        AccessRoles.deleteWhere { AccessRoles.id eq role[AccessRoles.id] } > 0
    }

    override fun roleExists(key: String): Boolean = transaction {
        AccessRoles.selectAll().where { AccessRoles.roleKey eq key }.count() > 0L
    }

    override fun hasPermission(email: String, permission: String): Boolean = transaction {
        val user = Users.selectAll().where { Users.email eq email }.singleOrNull() ?: return@transaction false
        if (!user[Users.active]) return@transaction false
        val role = AccessRoles.selectAll().where { AccessRoles.roleKey eq user[Users.role] }.singleOrNull() ?: return@transaction false
        if (!role[AccessRoles.isActive]) return@transaction false
        if (role[AccessRoles.isSuperAdmin]) return@transaction true
        RolePermissions.selectAll().where {
            (RolePermissions.roleId eq role[AccessRoles.id]) and (RolePermissions.permissionCode eq permission)
        }.count() > 0L
    }

    override fun permissionsForUser(email: String): Set<String> = transaction {
        val user = Users.selectAll().where { Users.email eq email }.singleOrNull() ?: return@transaction emptySet()
        if (!user[Users.active]) return@transaction emptySet()
        val role = AccessRoles.selectAll().where { AccessRoles.roleKey eq user[Users.role] }.singleOrNull() ?: return@transaction emptySet()
        if (!role[AccessRoles.isActive]) return@transaction emptySet()
        if (role[AccessRoles.isSuperAdmin]) return@transaction RbacPermissions.selectAll().map { it[RbacPermissions.code] }.toSet()
        RolePermissions.selectAll().where { RolePermissions.roleId eq role[AccessRoles.id] }
            .map { it[RolePermissions.permissionCode] }.toSet()
    }

    override fun isSuperAdmin(email: String): Boolean = transaction {
        val user = Users.selectAll().where { Users.email eq email }.singleOrNull() ?: return@transaction false
        user[Users.active] && AccessRoles.selectAll().where {
            (AccessRoles.roleKey eq user[Users.role]) and (AccessRoles.isSuperAdmin eq true) and (AccessRoles.isActive eq true)
        }.count() > 0L
    }

    private fun ResultRow.toAccessRole(): AccessRole {
        val id = this[AccessRoles.id]
        return AccessRole(
            id = id,
            key = this[AccessRoles.roleKey],
            displayName = this[AccessRoles.displayName],
            description = this[AccessRoles.description],
            superAdmin = this[AccessRoles.isSuperAdmin],
            active = this[AccessRoles.isActive],
            permissions = RolePermissions.selectAll().where { RolePermissions.roleId eq id }
                .map { it[RolePermissions.permissionCode] }.toSet(),
            createdAt = this[AccessRoles.createdAt],
            updatedAt = this[AccessRoles.updatedAt]
        )
    }
}
