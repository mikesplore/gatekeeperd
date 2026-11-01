package com.gatekeeper.feature.payment.data.provider

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PaystackWebhookSignatureVerifier {
    fun verify(rawBody: String, signature: String, secretKey: String): Boolean = try {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA512"))
        constantTimeEquals(bytesToHex(mac.doFinal(rawBody.toByteArray())), signature)
    } catch (_: Exception) {
        false
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        if (expected.length != actual.length) return false
        var result = 0
        for (index in expected.indices) result = result or (expected[index].code xor actual[index].code)
        return result == 0
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
