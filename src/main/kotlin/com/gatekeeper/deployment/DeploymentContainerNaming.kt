package com.gatekeeper.deployment

import java.util.UUID

/** Stable, readable Docker name for a deployment runtime. */
internal fun deploymentContainerName(serviceName: String, environment: String, deploymentId: UUID): String {
    fun slug(value: String, fallback: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
        .ifBlank { fallback }

    val environmentSlug = slug(environment, "production").take(16).trimEnd('-').ifBlank { "production" }
    val deploymentSuffix = deploymentId.toString().take(8)
    val serviceLimit = (63 - environmentSlug.length - deploymentSuffix.length - 2).coerceAtLeast(1)
    val serviceSlug = slug(serviceName, "service").take(serviceLimit).trimEnd('-').ifBlank { "service" }
    return "$serviceSlug-$environmentSlug-$deploymentSuffix"
}
