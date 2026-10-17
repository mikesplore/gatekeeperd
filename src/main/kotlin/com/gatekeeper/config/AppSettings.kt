package com.gatekeeper.config

import io.github.cdimascio.dotenv.dotenv

/**
 * Immutable snapshot of process configuration.
 *
 * Environment access is deliberately confined to [fromEnv]. The optional .env
 * file remains a local-development fallback, matching the legacy precedence.
 */
class AppSettings private constructor(
    private val values: Map<String, String>
) {
    operator fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppSettings {
            val local = dotenv {
                filename = ".env"
                ignoreIfMissing = true
            }
            val merged = buildMap {
                local.entries().forEach { entry -> put(entry.key, entry.value) }
                putAll(env)
            }
            return AppSettings(merged.toMap())
        }
    }
}
