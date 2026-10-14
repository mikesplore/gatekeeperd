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
    return parsePublishedPorts(portsField).values.toSet()
}

/** Parse host-to-container TCP/UDP port bindings from Docker's compact port field. */
fun parsePublishedPorts(portsField: String): Map<Int, Int> {
    if (portsField.isBlank()) return emptyMap()

    return portsField
        .split(",")
        .map { it.trim() }
        .mapNotNull { mapping ->
            val binding = mapping.substringBefore("/")
            val hostAndContainer = if ("->" in binding) {
                val (host, container) = binding.split("->", limit = 2)
                host.substringAfterLast(":") to container.substringAfterLast(":")
            } else {
                val parts = binding.split(":")
                if (parts.size < 2) return@mapNotNull null
                parts[parts.lastIndex - 1] to parts.last()
            }
            val hostPort = hostAndContainer.first.toIntOrNull() ?: return@mapNotNull null
            val containerPort = hostAndContainer.second.toIntOrNull() ?: return@mapNotNull null
            if (hostPort !in 1..65535 || containerPort !in 1..65535) return@mapNotNull null
            containerPort to hostPort
        }
        .toMap()
}
