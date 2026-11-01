package com.gatekeeper.admin.application


import org.slf4j.LoggerFactory

internal val projectAdminLogger = LoggerFactory.getLogger("com.gatekeeper.admin.ProjectAdminRoutes")

internal object ProjectAdminRules {
    val deploymentModes = setOf("developer_hosted", "client_hosted", "external_hosted")
    val serviceModes = setOf("development", "testing", "production")
    val lifecycleStatuses = setOf("active", "transferred", "archived", "cancelled")
}
