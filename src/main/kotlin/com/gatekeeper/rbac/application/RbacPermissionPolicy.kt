package com.gatekeeper.rbac.application

import io.ktor.http.HttpMethod

/** Maps every protected admin route to one catalogued permission. Unknown routes fail closed. */
object RbacPermissionPolicy {
    fun permissions(method: HttpMethod, rawPath: String): Set<String>? {
        val path = rawPath.trimEnd('/').ifBlank { "/" }
        if (!path.startsWith("/api/admin/")) return null
        val read = method == HttpMethod.Get || method == HttpMethod.Head || method == HttpMethod.Options
        val action = if (read) "read" else "manage"

        fun one(code: String) = setOf(code)
        fun dockerMutation(path: String): Set<String> {
            val highRisk = path == "/api/admin/containers/create" ||
                path.startsWith("/api/admin/project-setup/") && path.contains("/containers/create") ||
                path.startsWith("/api/admin/containers/") &&
                    (path.endsWith("/delete") || path.endsWith("/networks/move")) ||
                path == "/api/admin/networks/prune" || path == "/api/admin/volumes/prune" ||
                path == "/api/admin/networks" || path.startsWith("/api/admin/networks/") && method == HttpMethod.Delete ||
                path == "/api/admin/volumes" || path.startsWith("/api/admin/volumes/") && method == HttpMethod.Delete ||
                path == "/api/admin/images/pull"
            return if (highRisk) setOf("docker.manage", "system.manage") else one("docker.manage")
        }

        return when {
            path == "/api/admin/search" -> one("projects.read")
            path == "/api/admin/rbac/permissions" -> one("roles.read")
            path == "/api/admin/rbac/roles" || path.startsWith("/api/admin/rbac/roles/") -> one("roles.$action")
            path == "/api/admin/users" || path.startsWith("/api/admin/users/") -> one("users.$action")
            path.contains("/audit") || path.startsWith("/api/admin/audit") -> one("audit.read")
            path == "/api/admin/dashboard/summary" || path == "/api/admin/metrics" ||
                path.startsWith("/api/admin/deployments/reconciliation") -> one("operations.read")
            path.startsWith("/api/admin/projects/") && path.contains("/payments") -> one("payments.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/services/") && path.endsWith("/connection-details") -> one("deployments.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/services/") &&
                (path.contains("/payments") || path.contains("/payment/")) -> one("payments.$action")
            path.startsWith("/api/admin/services/") && path.contains("/payments") -> one("payments.$action")
            path.matches(Regex("^/api/admin/projects/[^/]+/services(?:/.*)?$")) -> one("services.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/services/") &&
                (path.contains("/invoice") || path.contains("/adjustments")) -> one("services.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/deployments/") -> one("deployments.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/secret-sets/") -> setOf("services.manage", "deployments.manage")
            path.startsWith("/api/admin/project-setup/") && path.contains("/containers") ->
                if (read) one("docker.read") else dockerMutation(path)
            path.startsWith("/api/admin/project-setup/") -> if (path.contains("/adopt-container")) {
                setOf("projects.manage", "docker.read")
            } else if (path.endsWith("/deploy")) {
                setOf("projects.manage", "deployments.manage")
            } else if (path.contains("/domain-gateway")) {
                setOf("projects.$action", "nginx.manage")
            } else {
                setOf("projects.$action", "services.$action")
            }
            path == "/api/admin/deployment-history" || path.startsWith("/api/admin/deployments/") ||
                path.startsWith("/api/admin/projects/") && (path.contains("/overview") || path.contains("/deployments")) -> one("deployments.$action")
            path.startsWith("/api/admin/dashboard/customers") || path.startsWith("/api/admin/customers") ->
                if (path.contains("/transactions")) one("payments.$action") else one("customers.$action")
            path.startsWith("/api/admin/dashboard/projects") -> one("projects.$action")
            path == "/api/admin/projects" || path == "/api/admin/projects/overdue" -> one("projects.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/environment/") -> one("services.$action")
            path.startsWith("/api/admin/projects/") && path.contains("/adjustments") -> one("payments.$action")
            path.startsWith("/api/admin/projects/") -> one("projects.$action")
            path.startsWith("/api/admin/dashboard/sites") || path.startsWith("/api/admin/dashboard/dead-configs") ||
                path.startsWith("/api/admin/nginx/") -> one("nginx.$action")
            path == "/api/admin/containers" || path.startsWith("/api/admin/containers/") ||
                path == "/api/admin/networks" || path.startsWith("/api/admin/networks/") ||
                path == "/api/admin/volumes" || path.startsWith("/api/admin/volumes/") ||
                path.startsWith("/api/admin/images/") -> if (read) one("docker.read") else dockerMutation(path)
            path.startsWith("/api/admin/payment-events") || path.startsWith("/api/admin/payments") ||
                path.startsWith("/api/admin/revenue") -> one("payments.$action")
            path.startsWith("/api/admin/integrations/") || path.startsWith("/api/admin/github/") ||
                path.startsWith("/api/admin/registries") || path.startsWith("/api/admin/provider-credentials") -> one("integrations.$action")
            path.startsWith("/api/admin/logs") -> one("logs.read")
            path == "/api/admin/system/deployment-diagnostics" -> one("logs.read")
            path.startsWith("/api/admin/notifications") -> one("notifications.$action")
            path.startsWith("/api/admin/system/") -> one("system.manage")
            else -> null
        }
    }
}
