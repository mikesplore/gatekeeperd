package com.gatekeeper.admin

internal fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
