package com.gatekeeper.admin.application


import com.gatekeeper.admin.model.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal val projectAdminLogger = LoggerFactory.getLogger("com.gatekeeper.admin.ProjectAdminRoutes")
internal val projectAdminJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

internal object ProjectAdminRules {
    val deploymentModes = setOf("developer_hosted", "client_hosted", "external_hosted")
    val serviceModes = setOf("development", "testing", "production")
    val lifecycleStatuses = setOf("active", "transferred", "archived", "cancelled")
}
