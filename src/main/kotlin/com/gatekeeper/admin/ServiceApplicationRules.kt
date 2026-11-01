package com.gatekeeper.admin

object ServiceApplicationRules {
    val supportedTypes = setOf("frontend", "backend", "worker", "database", "redis", "cache", "custom")

    fun validName(value: String) = value.length in 1..63 &&
        value.first().isLetterOrDigit() &&
        value.all { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.' } &&
        value.last().isLetterOrDigit()

    fun normalizeEnvironment(value: String) = value.trim().lowercase()
        .takeIf { it.matches(Regex("^[a-z][a-z0-9_-]{0,31}$")) }

    fun validEnvironmentValues(values: Map<String, String>) = values.all { (key, value) ->
        key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !key.contains('=') && !value.contains('\u0000')
    }
}
