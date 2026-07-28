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
