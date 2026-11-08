package com.gatekeeper.feature.payment.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpesaCallbackTokenTest {
    @Test
    fun `accepts only an exact configured bearer token of at least 32 bytes`() {
        val token = "a".repeat(48)

        assertTrue(mpesaCallbackTokenMatches(token, token))
        assertFalse(mpesaCallbackTokenMatches(token, token.dropLast(1)))
        assertFalse(mpesaCallbackTokenMatches(token, "b".repeat(token.length)))
        assertFalse(mpesaCallbackTokenMatches("short", "short"))
    }
}
