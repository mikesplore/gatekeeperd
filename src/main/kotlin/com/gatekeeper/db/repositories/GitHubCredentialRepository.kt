package com.gatekeeper.db.repositories

object GitHubCredentialRepository {
    const val APP_PRIVATE_KEY = "app_private_key"
    const val WEBHOOK_SECRET = "webhook_secret"
    private const val PROVIDER = "github"
    private const val GLOBAL_SCOPE = "global"

    fun privateKeyPem(): String? = find(APP_PRIVATE_KEY)
    fun webhookSecret(): String? = find(WEBHOOK_SECRET)

    fun save(type: String, value: String, actor: String = "admin"): ProviderCredentialMetadata {
        require(type == APP_PRIVATE_KEY || type == WEBHOOK_SECRET) { "Unsupported GitHub credential type" }
        require(value.isNotBlank()) { "Credential value must not be blank" }
        val displayName = when (type) {
            APP_PRIVATE_KEY -> "GitHub App private key"
            else -> "GitHub webhook secret"
        }
        return ProviderCredentialRepository.createOrRotate(PROVIDER, type, displayName, GLOBAL_SCOPE, value, actor)
    }

    private fun find(type: String): String? =
        ProviderCredentialRepository.findCurrent(PROVIDER, type, GLOBAL_SCOPE)?.payload
}
