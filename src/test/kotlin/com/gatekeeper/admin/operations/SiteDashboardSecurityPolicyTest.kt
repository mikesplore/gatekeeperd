package com.gatekeeper.admin.operations

import com.gatekeeper.admin.application.productionGateSecurityWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteDashboardSecurityPolicyTest {
    @Test
    fun `production gate disable needs explicit confirmation only for an enabled site`() {
        assertTrue(requiresProductionGateDisableConfirmation(true, true, false))
        assertFalse(requiresProductionGateDisableConfirmation(true, false, false))
        assertFalse(requiresProductionGateDisableConfirmation(true, true, true))
        assertFalse(requiresProductionGateDisableConfirmation(false, true, false))
    }

    @Test
    fun `production site dashboard flags payment gating that is disabled`() {
        assertEquals(null, productionGateSecurityWarning(true, true))
        assertEquals(null, productionGateSecurityWarning(false, false))
        assertTrue(productionGateSecurityWarning(true, false).orEmpty().contains("bypass billing enforcement"))
    }
}
