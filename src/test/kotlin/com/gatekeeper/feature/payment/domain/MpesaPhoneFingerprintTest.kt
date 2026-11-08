package com.gatekeeper.feature.payment.domain

import com.gatekeeper.feature.payment.domain.model.MpesaPhoneFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MpesaPhoneFingerprintTest {
    @Test
    fun `fingerprint is stable and secret keyed`() {
        val first = MpesaPhoneFingerprint.create("254712345678", "test-secret-a")

        assertEquals(first, MpesaPhoneFingerprint.create("254712345678", "test-secret-a"))
        assertNotEquals(first, MpesaPhoneFingerprint.create("254712345678", "test-secret-b"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

}
