package com.gatekeeper.nginx

import java.io.File
import java.nio.file.Files

/** Hard boundary protecting nginx vhosts that serve Gatekeeperd itself. */
class NginxSelfDomainExclusion(
    selfDomains: Collection<String>
) {
    private val selfDomains = selfDomains.map(::normalizeHost).filter(String::isNotBlank).toSet()

    fun excludesDomain(domain: String): Boolean {
        return selfDomains.any { host -> serverNameMatches(domain, host) }
    }

    fun excludesConfig(content: String): Boolean {
        val serverNames = Regex("(?m)^\\s*server_name\\s+([^;]+);")
            .findAll(content)
            .flatMap { it.groupValues[1].split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("\$") && it != "_" }
        return serverNames.any { pattern -> selfDomains.any { host -> serverNameMatches(pattern, host) } }
    }

    /** Unreadable config is treated as protected because its server names cannot be verified safely. */
    fun excludesFile(file: File): Boolean {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return false
        val content = runCatching { file.readText() }.getOrNull() ?: return true
        return excludesConfig(content)
    }

    private fun serverNameMatches(pattern: String, host: String): Boolean {
        val normalized = pattern.lowercase().trimEnd('.')
        return when {
            normalized.startsWith("*.") -> host.endsWith(normalized.removePrefix("*").let { ".$it" })
            normalized.startsWith(".") -> host == normalized.removePrefix(".") || host.endsWith(normalized)
            else -> normalizeHost(normalized) == host
        }
    }

    private fun normalizeHost(value: String): String = value.trim().trimEnd('.').lowercase()
}
