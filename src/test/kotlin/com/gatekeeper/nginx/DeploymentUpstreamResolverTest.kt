package com.gatekeeper.nginx

import com.gatekeeper.db.tables.UpstreamMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeploymentUpstreamResolverTest {
    private val projectId = UUID.randomUUID()
    private val dockerSite = DeploymentUpstreamResolver.SiteTarget(UpstreamMode.DOCKER_DISCOVERY, "127.0.0.1", null)

    @Test
    fun `docker site resolves assigned host port from active deployment runtime`() {
        val runtime = DeploymentUpstreamResolver.RuntimeTarget(
            projectId, "production", true, "candidate-container", 8080, mapOf(8080 to 49152)
        )

        assertEquals(
            DeploymentUpstreamResolver.Target("127.0.0.1", 49152, "candidate-container"),
            DeploymentUpstreamResolver.resolve(projectId, "production", dockerSite, runtime)
        )
    }

    @Test
    fun `explicit port target is returned unchanged without an active deployment`() {
        val explicitSite = DeploymentUpstreamResolver.SiteTarget(UpstreamMode.EXPLICIT_PORT, "10.0.0.8", 9000)

        assertEquals(
            DeploymentUpstreamResolver.Target("10.0.0.8", 9000),
            DeploymentUpstreamResolver.resolve(projectId, "production", explicitSite, null)
        )
    }

    @Test
    fun `active deployment pointer takes precedence after gateway cutover persisted explicit port`() {
        val siteAfterCutover = DeploymentUpstreamResolver.SiteTarget(UpstreamMode.EXPLICIT_PORT, "127.0.0.1", 49151)
        val runtime = DeploymentUpstreamResolver.RuntimeTarget(
            projectId, "production", true, "active-container", 8080, mapOf(8080 to 49152)
        )

        assertEquals(
            DeploymentUpstreamResolver.Target("127.0.0.1", 49152, "active-container"),
            DeploymentUpstreamResolver.resolve(projectId, "production", siteAfterCutover, runtime)
        )
    }

    @Test
    fun `docker site has no target when active deployment mapping is unavailable`() {
        val runtime = DeploymentUpstreamResolver.RuntimeTarget(
            projectId, "production", true, "active-container", 8080, emptyMap()
        )

        assertNull(DeploymentUpstreamResolver.resolve(projectId, "production", dockerSite, runtime))
    }

    @Test
    fun `docker site ignores deployment from a different project or environment`() {
        val otherProject = DeploymentUpstreamResolver.RuntimeTarget(
            UUID.randomUUID(), "production", true, "active-container", 8080, mapOf(8080 to 49152)
        )
        val otherEnvironment = otherProject.copy(projectId = projectId, environment = "staging")

        assertNull(DeploymentUpstreamResolver.resolve(projectId, "production", dockerSite, otherProject))
        assertNull(DeploymentUpstreamResolver.resolve(projectId, "production", dockerSite, otherEnvironment))
    }
}
