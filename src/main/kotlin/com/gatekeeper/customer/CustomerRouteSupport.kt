package com.gatekeeper.customer

import com.gatekeeper.config.AppSettings
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

internal fun frontendUrl(path: String, settings: AppSettings.IntegrationSettings): String? {
    val base = settings.frontendBaseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    return "$base/${path.trimStart('/')}"
}

