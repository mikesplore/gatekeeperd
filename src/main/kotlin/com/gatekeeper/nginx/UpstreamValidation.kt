package com.gatekeeper.nginx

/**
 * Projects currently store `containerName` as either:
 * - `my-container` (name only)
 * - `my-container:9921` (name + upstream host port)
 *
 * The nginx enable endpoint uses these helpers to validate that the container is running
 * and (when possible) that the expected host port is published.
 */
fun extractConfiguredPort(containerName: String): Int? =
    containerName.substringAfterLast(":", "").toIntOrNull()

fun extractConfiguredContainerName(containerName: String): String? {
    val raw = containerName.trim()
    if (raw.isBlank()) return null

    val maybePort = raw.substringAfterLast(":", missingDelimiterValue = "")
    val hasPortSuffix = maybePort.toIntOrNull() != null

    return if (hasPortSuffix) {
        raw.substringBeforeLast(":").trim().takeIf { it.isNotBlank() }
    } else {
        raw
    }
}

/**
 * `DockerService` currently exposes ports as a human string like:
 * - "9921->9921/tcp, 8080->8080/tcp"
 *
 * We treat the right-hand side of `->` as the *host* port.
 */
fun parsePublishedHostPorts(portsField: String): Set<Int> {
    if (portsField.isBlank()) return emptySet()

    return portsField
        .split(",")
        .map { it.trim() }
        .mapNotNull { mapping ->
            val parts = mapping.split("->", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hostPortPart = parts[1].substringBefore("/").trim()
            hostPortPart.toIntOrNull()
        }
        .toSet()
}

