package com.gatekeeper.security

import com.gatekeeper.config.AppConfig
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecretValueCipher {
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun isConfigured() = AppConfig.deploymentSecretsKey.isNotBlank()

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decrypt(encoded: String): String {
        val packed = Base64.getDecoder().decode(encoded)
        require(packed.size > IV_SIZE) { "Encrypted secret payload is invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(StandardCharsets.UTF_8)
    }

    fun redact(text: String, secrets: Collection<String>): String =
        secrets.filter(String::isNotEmpty).fold(text) { result, secret -> result.replace(secret, "[REDACTED]") }

    private fun key(): SecretKeySpec {
        val bytes = runCatching { Base64.getDecoder().decode(AppConfig.deploymentSecretsKey) }
            .getOrElse { throw IllegalStateException("DEPLOYMENT_SECRETS_KEY must be base64 encoded") }
        require(bytes.size == 32) { "DEPLOYMENT_SECRETS_KEY must decode to exactly 32 bytes" }
        return SecretKeySpec(bytes, "AES")
    }
}
