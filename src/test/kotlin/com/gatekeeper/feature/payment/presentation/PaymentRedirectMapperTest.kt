package com.gatekeeper.feature.payment.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaymentRedirectMapperTest {
    @Test
    fun `normalizes a project domain to https`() {
        assertEquals("https://example.com", projectRedirectUrl("example.com"))
    }

    @Test
    fun `preserves an explicit http project URL`() {
        assertEquals("http://localhost:8081", projectRedirectUrl("http://localhost:8081"))
    }

    @Test
    fun `uses http for a bare localhost redirect`() {
        assertEquals("http://localhost:8081", projectRedirectUrl("localhost:8081"))
    }

    @Test
    fun `rejects blank or malformed project domains`() {
        assertNull(projectRedirectUrl("  "))
        assertNull(projectRedirectUrl("https://"))
        assertNull(projectRedirectUrl("https://user@example.com"))
        assertNull(projectRedirectUrl("scribed"))
    }
}
