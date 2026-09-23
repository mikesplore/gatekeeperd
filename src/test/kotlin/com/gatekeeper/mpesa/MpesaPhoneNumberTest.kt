package com.gatekeeper.mpesa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MpesaPhoneNumberTest {
    @Test
    fun `normalizes Kenyan mobile number formats`() {
        assertEquals("254712345678", MpesaPhoneNumber.normalize("0712345678"))
        assertEquals("254112345678", MpesaPhoneNumber.normalize("0112345678"))
        assertEquals("254712345678", MpesaPhoneNumber.normalize("+254 712 345 678"))
        assertEquals("254712345678", MpesaPhoneNumber.normalize("254712345678"))
    }

    @Test
    fun `rejects malformed and non Kenyan mobile numbers`() {
        assertNull(MpesaPhoneNumber.normalize("071234567"))
        assertNull(MpesaPhoneNumber.normalize("254212345678"))
        assertNull(MpesaPhoneNumber.normalize("+1 212 555 0100"))
        assertNull(MpesaPhoneNumber.normalize("not a phone"))
    }
}
