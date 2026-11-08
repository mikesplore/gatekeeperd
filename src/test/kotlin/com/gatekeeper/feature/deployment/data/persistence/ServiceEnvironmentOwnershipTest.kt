package com.gatekeeper.feature.deployment.data.persistence

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceEnvironmentOwnershipTest {
    @Test
    fun `ownerless configuration is legacy data for default service only`() {
        val deletedServiceId = UUID.randomUUID()
        val replacementServiceId = UUID.randomUUID()

        assertFalse(belongsToServiceEnvironmentConfiguration(null, replacementServiceId, false))
        assertTrue(belongsToServiceEnvironmentConfiguration(null, replacementServiceId, true))
        assertTrue(belongsToServiceEnvironmentConfiguration(replacementServiceId, replacementServiceId, false))
        assertFalse(belongsToServiceEnvironmentConfiguration(deletedServiceId, replacementServiceId, false))
    }
}
