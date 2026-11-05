package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.data.persistence.preferredDeploymentConfiguration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferredDeploymentConfigurationTest {
    private data class Configuration(
        val id: String,
        val updatedAt: LocalDateTime,
        val createdAt: LocalDateTime
    )

    @Test
    fun `service configuration takes precedence over newer legacy project configuration`() {
        val oldActiveServiceConfig = configuration("active-service", "2026-11-01T10:00:00", "2026-10-30T10:00:00")
        val newerLegacyConfig = configuration("failed-legacy", "2026-11-05T10:00:00", "2026-10-20T10:00:00")

        val selected = preferredDeploymentConfiguration(
            serviceConfigurations = listOf(oldActiveServiceConfig),
            legacyConfigurations = listOf(newerLegacyConfig),
            updatedAt = Configuration::updatedAt,
            createdAt = Configuration::createdAt
        )

        assertEquals("active-service", selected?.id)
    }

    @Test
    fun `latest service configuration wins when historical service rows exist`() {
        val previous = configuration("previous", "2026-11-01T10:00:00", "2026-10-30T10:00:00")
        val current = configuration("current", "2026-11-04T10:00:00", "2026-11-04T10:00:00")

        val selected = preferredDeploymentConfiguration(
            serviceConfigurations = listOf(previous, current),
            legacyConfigurations = emptyList(),
            updatedAt = Configuration::updatedAt,
            createdAt = Configuration::createdAt
        )

        assertEquals("current", selected?.id)
    }

    private fun configuration(id: String, updatedAt: String, createdAt: String) = Configuration(
        id,
        LocalDateTime.parse(updatedAt),
        LocalDateTime.parse(createdAt)
    )
}
