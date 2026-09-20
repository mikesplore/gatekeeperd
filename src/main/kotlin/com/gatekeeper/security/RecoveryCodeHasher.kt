package com.gatekeeper.security

import org.mindrot.jbcrypt.BCrypt

/** Recovery codes are only ever persisted as BCrypt hashes. */
object RecoveryCodeHasher {
    fun hash(code: String): String = BCrypt.hashpw(normalize(code), BCrypt.gensalt(12))

    fun matches(code: String, hash: String): Boolean =
        runCatching { BCrypt.checkpw(normalize(code), hash) }.getOrDefault(false)

    private fun normalize(code: String): String = code.trim().uppercase()
}
