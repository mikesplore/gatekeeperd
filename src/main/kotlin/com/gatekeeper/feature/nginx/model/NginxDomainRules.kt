package com.gatekeeper.feature.nginx.model

import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.presentation.NginxCustomLocation
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import java.io.File
import kotlin.collections.forEach

class DomainRules(
    private val nginxSelfDomainExclusion: NginxSelfDomainExclusion,
    private val siteRepository: SiteRepository
) {

    fun isSafeConfigFilename(filename: String): Boolean =
        filename == File(filename).name && filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*"))

    fun normalizeServerName(name: String): String =
        name.trim().trimEnd('.').lowercase()

    fun domainsOverlap(first: List<String>, second: List<String>): Boolean =
        first.any { left ->
            second.any { right -> serverNamesMayMatch(normalizeServerName(left), normalizeServerName(right)) }
        }

    fun serverNamesMayMatch(first: String, second: String): Boolean = when {
        first == second -> true
        first.startsWith("*.") -> second.endsWith(first.removePrefix("*"))
        second.startsWith("*.") -> first.endsWith(second.removePrefix("*"))
        first.startsWith(".") -> second == first.removePrefix(".") || second.endsWith(first)
        second.startsWith(".") -> first == second.removePrefix(".") || first.endsWith(second)
        else -> false
    }

    fun portsOverlap(first: List<Int>, second: List<Int>): Boolean =
        first.isEmpty() || second.isEmpty() || first.any { it in second }

    fun serverNamePatternsOverlap(first: String, second: String): Boolean {
        val a = normalizeServerName(first)
        val b = normalizeServerName(second)
        if (a.startsWith("~") || b.startsWith("~")) return true
        if (serverNamePatternMatches(a, b) || serverNamePatternMatches(b, a)) return true
        val aWildcardBase = a.removePrefix("*.").removePrefix(".")
        val bWildcardBase = b.removePrefix("*.").removePrefix(".")
        val aWildcard = a.startsWith("*.") || a.startsWith(".")
        val bWildcard = b.startsWith("*.") || b.startsWith(".")
        return aWildcard && bWildcard && (
                aWildcardBase == bWildcardBase ||
                        aWildcardBase.endsWith(".$bWildcardBase") ||
                        bWildcardBase.endsWith(".$aWildcardBase")
                )
    }

    fun serverNamePatternMatches(pattern: String, hostname: String): Boolean = when {
        pattern.startsWith("*.") -> hostname.endsWith(pattern.removePrefix("*").let { ".$it" })
        pattern.startsWith(".") -> hostname == pattern.removePrefix(".") || hostname.endsWith(pattern)
        else -> pattern == hostname
    }

    fun validateCustomLocations(locations: List<NginxCustomLocation>) {
        require(locations.size <= 20) { "A site can have at most 20 custom location blocks" }
        locations.forEach(::validateCustomLocation)
        require(locations.map { it.path.lowercase() }
            .distinct().size == locations.size) { "Custom location paths must be unique" }
    }

    fun validateCustomLocation(location: NginxCustomLocation) {
        require(location.id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid custom location ID" }
        require(
            location.path.matches(Regex("/(?:[A-Za-z0-9._~-]+/?)+")) &&
                    location.path.split('/').none { it == "." || it == ".." }) {
            "Location path must be a literal URI prefix such as /socket.io/"
        }
        require(location.path != "/") { "The root location is reserved for the application route" }
        val reserved = listOf(
            "/api/gate",
            "/api/paystack",
            "/api/mpesa",
            "/gatekeeper-auth-",
            "/.well-known/gatekeeperd-cutover-probe"
        )
        require(reserved.none {
            location.path.startsWith(
                it,
                ignoreCase = true
            )
        }) { "This path is reserved for Gatekeeper routes" }
        require(location.directives.isNotBlank() && location.directives.length <= 16_000) { "Add directives (maximum 16,000 characters)" }
        val forbidden =
            Regex("(?im)^\\s*(?:location|server|listen|include|auth_request|error_page|satisfy|allow|deny|return|rewrite|try_files|internal)\\b")
        require(!forbidden.containsMatchIn(location.directives)) { "Custom locations cannot define nested routes or override Gatekeeper access control" }
        require(!location.directives.contains('{') && !location.directives.contains('}')) { "Nested nginx blocks are not supported in custom locations" }
        require(
            location.directives.lineSequence()
                .all { it.isBlank() || it.trimEnd().endsWith(';') }) { "Each directive must end with a semicolon" }
        val proxyPasses =
            location.directives.lineSequence().filter { it.trimStart().matches(Regex("^proxy_pass(?:\\s|$).*")) }
        require(proxyPasses.none()) {
            "The application upstream is added automatically; custom proxy_pass directives are not allowed"
        }
    }

    fun validateProtectedGateBlocks(slug: String, config: String) {
        val gateEnabled = runCatching { siteRepository.findByProjectSlug(slug)?.gateEnabled }.getOrNull() ?: true
        if (!gateEnabled) return
        require("# gatekeeperd:block:auth_request" in config && "# gatekeeperd:block:paywall" in config) {
            "This backup is missing Gatekeeper's protected auth or paywall block"
        }
        require(
            Regex("(?m)^\\s*auth_request /gatekeeper-auth-${Regex.escape(slug)};\\s*$").containsMatchIn(config) &&
                    Regex("(?m)^\\s*error_page 403 = @gatekeeper_paywall_${Regex.escape(slug)};\\s*$").containsMatchIn(
                        config
                    )
        ) {
            "This backup does not preserve Gatekeeper's auth and paywall directives"
        }
    }

    fun isExcluded(vararg files: File): Boolean =
        files.any(nginxSelfDomainExclusion::excludesFile)
}