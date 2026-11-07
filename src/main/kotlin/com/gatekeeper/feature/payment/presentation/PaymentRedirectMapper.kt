package com.gatekeeper.feature.payment.presentation

import java.net.URI

internal fun projectRedirectUrl(domain: String): String? {
    val trimmed = domain.trim()
    if (trimmed.isBlank()) return null
    val candidate = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        val host = runCatching { URI("//$trimmed").host }.getOrNull() ?: return null
        if (host != "localhost" && !host.contains('.') && !host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) return null
        val scheme = if (host == "localhost" || host == "127.0.0.1") "http" else "https"
        "$scheme://$trimmed"
    }
    return runCatching {
        URI(candidate).takeIf {
            it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true)
        }?.takeIf { !it.host.isNullOrBlank() && it.rawUserInfo == null }
            ?.toASCIIString()
    }.getOrNull()
}
