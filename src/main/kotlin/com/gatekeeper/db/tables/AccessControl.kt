package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object AccessRoles : Table("access_roles") {
    val id = uuid("id").autoGenerate()
    val roleKey = text("role_key").uniqueIndex()
    val displayName = text("display_name")
    val description = text("description").nullable()
    val isSuperAdmin = bool("is_super_admin").default(false)
    val isActive = bool("is_active").default(true)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

object RbacPermissions : Table("rbac_permissions") {
    val code = text("code")
    val category = text("category")
    val action = text("action")
    val description = text("description")

    override val primaryKey = PrimaryKey(code)
}

object RolePermissions : Table("role_permissions") {
    val roleId = reference("role_id", AccessRoles.id, onDelete = ReferenceOption.CASCADE)
    val permissionCode = reference("permission_code", RbacPermissions.code, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(roleId, permissionCode)
}
