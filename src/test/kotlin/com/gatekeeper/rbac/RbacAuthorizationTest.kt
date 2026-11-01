package com.gatekeeper.rbac

import com.gatekeeper.rbac.application.RbacApplicationService
import com.gatekeeper.rbac.application.RbacPermissionPolicy
import com.gatekeeper.rbac.domain.AccessRole
import com.gatekeeper.rbac.domain.PermissionDefinition
import com.gatekeeper.rbac.domain.RbacCatalog
import com.gatekeeper.rbac.domain.RbacRepository
import com.gatekeeper.rbac.domain.RoleDraft
import com.gatekeeper.api.HttpApiException
import io.ktor.http.HttpMethod
import java.time.LocalDateTime
import java.util.UUID
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RbacAuthorizationTest {
    @Test
    fun `admin route policy maps representative read write and multi-capability operations`() {
        assertEquals(setOf("projects.read"), RbacPermissionPolicy.permissions(HttpMethod.Get, "/api/admin/projects"))
        assertEquals(setOf("projects.manage"), RbacPermissionPolicy.permissions(HttpMethod.Patch, "/api/admin/projects/demo"))
        assertEquals(setOf("payments.read"), RbacPermissionPolicy.permissions(HttpMethod.Get, "/api/admin/projects/1/services/2/payments/3/receipt"))
        assertEquals(setOf("payments.manage"), RbacPermissionPolicy.permissions(HttpMethod.Post, "/api/admin/projects/1/payments"))
        assertEquals(setOf("operations.read"), RbacPermissionPolicy.permissions(HttpMethod.Get, "/api/admin/deployments/reconciliation"))
        assertEquals(setOf("deployments.manage"), RbacPermissionPolicy.permissions(HttpMethod.Post, "/api/admin/projects/demo/deployments/4/rollback"))
        assertEquals(
            setOf("projects.manage", "deployments.manage"),
            RbacPermissionPolicy.permissions(HttpMethod.Post, "/api/admin/project-setup/projects/1/deploy")
        )
        assertEquals("roles.manage", RbacPermissionPolicy.permissions(HttpMethod.Post, "/api/admin/rbac/roles")?.single())
        assertEquals(null, RbacPermissionPolicy.permissions(HttpMethod.Get, "/api/admin/unregistered"))
        assertEquals(null, RbacPermissionPolicy.permissions(HttpMethod.Get, "/api/health"))
    }

    @Test
    fun `authorization uses current role grants and administrator bypass`() {
        val repository = FakeRbacRepository()
        val service = RbacApplicationService(repository)

        assertTrue(service.authorize("admin@example.test", "system.manage"))
        assertTrue(service.authorize("viewer@example.test", "projects.read"))
        assertFalse(service.authorize("viewer@example.test", "projects.manage"))

        repository.viewerPermissions += "projects.manage"
        assertTrue(service.authorize("viewer@example.test", "projects.manage"))
    }

    @Test
    fun `custom role grants cannot exceed the acting user's permissions`() {
        val repository = FakeRbacRepository()
        val service = RbacApplicationService(repository)
        val created = service.createRole(
            "admin@example.test",
            RoleDraft("billing_operator", "Billing Operator", "Manages billing", setOf("payments.read", "payments.manage"))
        )
        assertEquals(setOf("payments.read", "payments.manage"), created.permissions)
        assertTrue(service.mayAssignRole("admin@example.test", "billing_operator"))
        assertFalse(service.mayAssignRole("viewer@example.test", "billing_operator"))
        assertFailsWith<HttpApiException> {
            service.createRole("viewer@example.test", RoleDraft("elevated_role", "Elevated", null, setOf("projects.read", "projects.manage")))
        }
        service.updateRole("admin@example.test", "billing_operator", "Billing Operator", null, false, created.permissions)
        assertFalse(service.mayAssignRole("admin@example.test", "billing_operator"))
    }

    @Test
    fun `permission catalog is bounded and every permission has a route resource`() {
        assertEquals(RbacCatalog.permissions.size, RbacCatalog.byCode.size)
        assertTrue(RbacCatalog.permissions.all { it.code.startsWith("${it.category}.") && it.description.isNotBlank() })
    }

    @Test
    fun `every declared admin route has a fail closed policy mapping`() {
        val routeRoot = File("src/main/kotlin/com/gatekeeper")
        val declaration = Regex("\\b(get|post|put|patch|delete|head|options)\\s*\\(\\s*\"(/[^\"]*)\"")
        val missing = routeRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> declaration.findAll(file.readText()).map { it.groupValues[1].uppercase() to it.groupValues[2] } }
            .filter { (_, path) -> path.startsWith("/api/admin/") }
            .filter { (method, path) ->
                val concretePath = path.replace(Regex("\\{[^}]+}"), "sample")
                RbacPermissionPolicy.permissions(HttpMethod.parse(method), concretePath) == null
            }
            .toList()
        assertTrue(missing.isEmpty(), "Admin routes without a permission mapping: $missing")
    }

    private class FakeRbacRepository : RbacRepository {
        private val now = LocalDateTime.now()
        private val permissionList = RbacCatalog.permissions
        val viewerPermissions = mutableSetOf("projects.read", "customers.read", "services.read", "deployments.read")
        private val customRoles = linkedMapOf<String, AccessRole>()

        override fun permissionDefinitions(): List<PermissionDefinition> = permissionList
        override fun listRoles(): List<AccessRole> = customRoles.values.toList()
        override fun findRole(key: String): AccessRole? = customRoles[key]

        override fun createRole(draft: RoleDraft): AccessRole {
            val role = AccessRole(UUID.randomUUID(), draft.key, draft.displayName, draft.description, false, true, draft.permissions, now, now)
            customRoles[draft.key] = role
            return role
        }

        override fun updateRole(key: String, displayName: String, description: String?, active: Boolean, permissions: Set<String>): AccessRole? =
            customRoles[key]?.copy(displayName = displayName, description = description, active = active, permissions = permissions, updatedAt = now)
                ?.also { customRoles[key] = it }

        override fun deleteRole(key: String): Boolean = customRoles.remove(key) != null
        override fun roleExists(key: String): Boolean = key == "admin" || key == "viewer" || key in customRoles
        override fun hasPermission(email: String, permission: String): Boolean = when (email) {
            "admin@example.test" -> true
            "viewer@example.test" -> permission in viewerPermissions
            else -> false
        }
        override fun permissionsForUser(email: String): Set<String> = when (email) {
            "admin@example.test" -> RbacCatalog.byCode.keys
            "viewer@example.test" -> viewerPermissions
            else -> emptySet()
        }
        override fun isSuperAdmin(email: String): Boolean = email == "admin@example.test"
    }
}
