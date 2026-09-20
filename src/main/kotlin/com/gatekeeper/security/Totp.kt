package com.gatekeeper.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val random = SecureRandom()

    fun newSecret(): String = encode(ByteArray(20).also(random::nextBytes))

    fun code(secret: String, timeMillis: Long = System.currentTimeMillis()): String {
        val counter = timeMillis / 1000 / 30
        val hash = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(decode(secret), "HmacSHA1"))
        }.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return binary.toString().padStart(6, '0').takeLast(6)
    }

    fun verify(secret: String, supplied: String, now: Long = System.currentTimeMillis()): Boolean {
        val normalized = supplied.trim()
        if (!normalized.matches(Regex("\\d{6}"))) return false
        return (-1L..1L).any { step -> code(secret, now + step * 30_000L) == normalized }
    }

    private fun encode(bytes: ByteArray): String {
        val result = StringBuilder()
        var buffer = 0; var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; result.append(ALPHABET[(buffer shr bits) and 31]) }
        }
        if (bits > 0) result.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return result.toString()
    }

    private fun decode(value: String): ByteArray {
        val cleaned = value.trim().uppercase().replace("=", "")
        val output = ArrayList<Byte>(); var buffer = 0; var bits = 0
        cleaned.forEach { char ->
            val index = ALPHABET.indexOf(char); require(index >= 0) { "Invalid base32 secret" }
            buffer = (buffer shl 5) or index; bits += 5
            if (bits >= 8) { bits -= 8; output.add(((buffer shr bits) and 0xff).toByte()) }
        }
        return output.toByteArray()
    }
}
