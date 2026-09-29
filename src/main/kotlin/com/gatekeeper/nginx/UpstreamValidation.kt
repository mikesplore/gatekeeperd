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
 * Docker port strings may use `->` (for example, `0.0.0.0:32768->9921/tcp`)
 * or the compact `host:container/protocol` format returned by DockerService
 * (for example, `32768:9921/tcp`). In both formats, return the published host port.
 */
fun parsePublishedHostPorts(portsField: String): Set<Int> {
    if (portsField.isBlank()) return emptySet()

    return portsField
        .split(",")
        .map { it.trim() }
        .mapNotNull { mapping ->
            val hostPortPart = when {
                "->" in mapping -> mapping.substringBefore("->").substringAfterLast(":")
                ":" in mapping -> mapping.substringBefore("/").substringBeforeLast(":").substringAfterLast(":")
                else -> return@mapNotNull null
            }
            hostPortPart.trim().toIntOrNull()
        }
        .toSet()
}
