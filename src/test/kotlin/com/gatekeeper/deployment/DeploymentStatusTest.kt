package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentStatusTest {
    @Test
    fun `transition graph accepts and rejects expected transitions`() {
        val cases = listOf(
            DeploymentStatus.QUEUED to setOf(DeploymentStatus.BUILDING, DeploymentStatus.CANCELLED),
            DeploymentStatus.BUILDING to setOf(DeploymentStatus.STARTING, DeploymentStatus.FAILED, DeploymentStatus.CANCELLED),
            DeploymentStatus.STARTING to setOf(DeploymentStatus.HEALTH_CHECKING, DeploymentStatus.FAILED, DeploymentStatus.CANCELLED),
            DeploymentStatus.HEALTH_CHECKING to setOf(
                DeploymentStatus.QUEUED, DeploymentStatus.READY, DeploymentStatus.ACTIVE,
                DeploymentStatus.FAILED, DeploymentStatus.CANCELLED
            ),
            DeploymentStatus.READY to setOf(DeploymentStatus.ACTIVE, DeploymentStatus.FAILED),
            DeploymentStatus.ACTIVE to setOf(DeploymentStatus.SUPERSEDED, DeploymentStatus.ROLLED_BACK),
            DeploymentStatus.SUPERSEDED to setOf(DeploymentStatus.ACTIVE, DeploymentStatus.ROLLED_BACK),
            DeploymentStatus.FAILED to setOf(DeploymentStatus.QUEUED),
            DeploymentStatus.CANCELLED to setOf(DeploymentStatus.QUEUED),
            DeploymentStatus.ROLLED_BACK to emptySet()
        )

        cases.forEach { (from, allowed) ->
            DeploymentStatus.entries.forEach { to ->
                assertEquals(
                    to in allowed,
                    from.canTransition(to),
                    "Unexpected transition ${from.value} -> ${to.value}"
                )
                assertEquals(
                    to in allowed,
                    DeploymentStatus.canTransition(from, to),
                    "Companion mismatch for ${from.value} -> ${to.value}"
                )
            }
        }
    }
}
