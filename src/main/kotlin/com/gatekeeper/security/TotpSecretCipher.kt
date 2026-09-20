package com.gatekeeper.security

/** Encrypts TOTP secrets with the configured AES-256-GCM deployment key. */
object TotpSecretCipher {
    fun isConfigured(): Boolean = SecretValueCipher.isConfigured()

    fun encrypt(secret: String): String {
        require(secret.isNotBlank()) { "TOTP secret must not be blank" }
        check(isConfigured()) { "DEPLOYMENT_SECRETS_KEY must be configured" }
        return SecretValueCipher.encrypt(secret)
    }

    fun decrypt(ciphertext: String): String {
        check(isConfigured()) { "DEPLOYMENT_SECRETS_KEY must be configured" }
        return SecretValueCipher.decrypt(ciphertext)
    }
}
