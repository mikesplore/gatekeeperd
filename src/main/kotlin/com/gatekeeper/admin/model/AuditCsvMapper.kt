package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
internal fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
