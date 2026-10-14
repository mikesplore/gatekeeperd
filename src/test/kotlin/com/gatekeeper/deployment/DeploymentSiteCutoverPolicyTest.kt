package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
}
