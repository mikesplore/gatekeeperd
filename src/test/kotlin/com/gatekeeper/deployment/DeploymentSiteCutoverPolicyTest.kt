package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.UUID

class DeploymentSiteCutoverPolicyTest {
    @Test
    fun `explicit port sites with deployment config enter stable upstream cutover`() {
        assertTrue(DeploymentSiteCutoverPolicy.hasDeploymentRoute("EXPLICIT_PORT"))
        assertTrue(DeploymentSiteCutoverPolicy.convertsLegacySite("EXPLICIT_PORT"))
        assertEquals(8000, DeploymentSiteCutoverPolicy.primaryContainerPort(null, "EXPLICIT_PORT", 8000))
    }

    @Test
    fun `deployment managed configured primary port takes precedence`() {
        assertEquals(9000, DeploymentSiteCutoverPolicy.primaryContainerPort(9000, "DEPLOYMENT_MANAGED", 8000))
    }

    @Test
    fun `sites without an upstream mode do not enter deployment cutover`() {
        assertFalse(DeploymentSiteCutoverPolicy.hasDeploymentRoute(null))
        assertNull(DeploymentSiteCutoverPolicy.primaryContainerPort(null, null, 8000))
    }

    @Test
    fun `stable service upstream in nginx config triggers cutover even when database kind is stale`() {
        val serviceId = UUID.fromString("6ec4b9e9-d57f-43b2-8a16-3e3e9dd7e897")
        val config = """
            location / {
                proxy_pass http://gk_6ec4b9e9d57f43b28a163e3e9dd7e897;
            }
        """.trimIndent()

        val referencesStableUpstream = DeploymentSiteCutoverPolicy.referencesServiceUpstream(config, serviceId)

        assertTrue(referencesStableUpstream)
        assertTrue(DeploymentSiteCutoverPolicy.convertsLegacySite(null, referencesStableUpstream))
    }

    @Test
    fun `another service upstream does not trigger cutover`() {
        val serviceId = UUID.fromString("6ec4b9e9-d57f-43b2-8a16-3e3e9dd7e897")
        val config = """
            location / {
                proxy_pass http://gk_differentserviceid;
            }
        """.trimIndent()

        assertFalse(DeploymentSiteCutoverPolicy.referencesServiceUpstream(config, serviceId))
    }
}
