package com.gatekeeper.rbac.domain

import java.time.LocalDateTime
import java.util.UUID

data class PermissionDefinition(
    val code: String,
    val category: String,
    val action: String,
    val description: String
)

data class AccessRole(
    val id: UUID,
    val key: String,
    val displayName: String,
    val description: String?,
    val superAdmin: Boolean,
    val active: Boolean,
    val permissions: Set<String>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class RoleDraft(
    val key: String,
    val displayName: String,
    val description: String?,
    val permissions: Set<String>
)

object RbacCatalog {
    val permissions = listOf(
        PermissionDefinition("roles.read", "roles", "read", "View role definitions and available permissions."),
        PermissionDefinition("roles.manage", "roles", "manage", "Create roles and change their permission grants."),
        PermissionDefinition("users.read", "users", "read", "View user accounts."),
        PermissionDefinition("users.manage", "users", "manage", "Create, update, suspend, delete, and assign roles to users."),
        PermissionDefinition("projects.read", "projects", "read", "View projects, project status, and project audit history."),
        PermissionDefinition("projects.manage", "projects", "manage", "Create, update, block, transfer, archive, and configure projects."),
        PermissionDefinition("customers.read", "customers", "read", "View customer records and customer dashboards."),
        PermissionDefinition("customers.manage", "customers", "manage", "Create customers and change customer-project assignments."),
        PermissionDefinition("services.read", "services", "read", "View service configuration, environments, and billing details."),
        PermissionDefinition("services.manage", "services", "manage", "Change services, domains, environments, secrets, and service billing."),
        PermissionDefinition("deployments.read", "deployments", "read", "View deployment jobs, histories, and runtime status."),
        PermissionDefinition("deployments.manage", "deployments", "manage", "Start, cancel, redeploy, roll back, or remove deployments."),
        PermissionDefinition("payments.read", "payments", "read", "View payments, payment events, receipts, and revenue."),
        PermissionDefinition("payments.manage", "payments", "manage", "Record, reconcile, replay, and initialize payments."),
        PermissionDefinition("nginx.read", "nginx", "read", "View nginx sites, configs, certificates, and diagnostics."),
        PermissionDefinition("nginx.manage", "nginx", "manage", "Change nginx sites, configurations, certificates, and upstreams."),
        PermissionDefinition("docker.read", "docker", "read", "View containers, networks, volumes, images, and logs."),
        PermissionDefinition("docker.manage", "docker", "manage", "Create, delete, start, stop, restart, or pull Docker resources."),
        PermissionDefinition("integrations.read", "integrations", "read", "View provider credential metadata and integration events."),
        PermissionDefinition("integrations.manage", "integrations", "manage", "Change provider credentials and replay integration events."),
        PermissionDefinition("logs.read", "logs", "read", "Read application and system logs."),
        PermissionDefinition("audit.read", "audit", "read", "Read and export audit records."),
        PermissionDefinition("notifications.read", "notifications", "read", "View notification records and delivery state."),
        PermissionDefinition("notifications.manage", "notifications", "manage", "Change notification state and delivery handling."),
        PermissionDefinition("operations.read", "operations", "read", "View system metrics, dashboards, and operational status."),
        PermissionDefinition("system.manage", "system", "manage", "Run system-level operations such as cleanup and manual deployment.")
    )
    val byCode = permissions.associateBy(PermissionDefinition::code)
}
