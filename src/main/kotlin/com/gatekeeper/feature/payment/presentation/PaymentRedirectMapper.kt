package com.gatekeeper.feature.payment.presentation

internal fun projectRedirectUrl(domain: String): String {
    val trimmed = domain.trim()
    return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
}
