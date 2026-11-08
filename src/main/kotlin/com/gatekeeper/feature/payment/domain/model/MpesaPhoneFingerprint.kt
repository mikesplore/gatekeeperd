package com.gatekeeper.feature.payment.domain.model

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC prevents a database dump from being used to enumerate Kenyan phone numbers. */
object MpesaPhoneFingerprint {
    fun create(normalizedPhone: String, secret: String): String {
        require(secret.isNotBlank()) { "A fingerprint secret is required" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(normalizedPhone.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
