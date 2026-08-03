package com.gatekeeper.api

import java.time.LocalDate
import java.time.format.DateTimeParseException

object InputValidators {
    private val SLUG_REGEX = Regex("^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$")
    private val CONTAINER_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")
    private val IMAGE_NAME_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,254}$")
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun normalizeSlug(raw: String): String? {
        val slug = raw.lowercase().trim()
        return slug.takeIf { SLUG_REGEX.matches(it) }
    }

    fun isValidContainerName(name: String): Boolean = CONTAINER_NAME_REGEX.matches(name.trim())

    /**
     * Accepts:
     * - `my-container`
     * - `my-container:9921` (port must be 1..65535)
     *
     * Used for the project `containerName` field, which can optionally encode a host port.
     */
    fun isValidContainerRef(ref: String): Boolean {
        val raw = ref.trim()
        if (raw.isBlank()) return false

        val lastColon = raw.lastIndexOf(':')
        val hasPort = lastColon > 0 && raw.substring(lastColon + 1).toIntOrNull() != null

        return if (hasPort) {
            val name = raw.substring(0, lastColon).trim()
            val port = raw.substring(lastColon + 1).toIntOrNull()
            isValidContainerName(name) && port != null && port in 1..65535
        } else {
            isValidContainerName(raw)
        }
    }

    fun isValidImageName(name: String): Boolean = IMAGE_NAME_REGEX.matches(name.trim())

    fun isValidProjectType(type: String): Boolean =
        type.lowercase() in listOf("frontend", "backend")

    fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())

    fun parseDueDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun requireNonBlank(value: String, field: String): String? {
        val trimmed = value.trim()
        return trimmed.takeIf { it.isNotEmpty() }
    }
}
