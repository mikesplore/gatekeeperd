package com.gatekeeper.feature.deployment.domain.repository

import java.util.UUID

interface DeploymentPortAllocationRepository {
    fun allocateSlot(
        serviceId: UUID,
        environment: String,
        activePorts: Map<Int, Int>,
        containerPorts: Set<Int>,
        dockerPortsInUse: Set<Int>,
        portRange: IntRange,
        rejectedPorts: Set<Int> = emptySet()
    ): Map<Int, Int>
}
