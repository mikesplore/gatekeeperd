package com.gatekeeper.feature.deployment.data.persistence

import com.gatekeeper.db.tables.DeploymentPortAllocations
import com.gatekeeper.deployment.DeploymentHostPortPool
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import com.gatekeeper.feature.deployment.domain.repository.DeploymentPortAllocationRepository as DeploymentPortAllocationPort
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedDeploymentPortAllocationRepository : DeploymentPortAllocationPort {
    /** Reserves/reuses both slots atomically; the DB lock coordinates all Gatekeeperd instances. */
    override fun allocateSlot(
        serviceId: UUID,
        environment: String,
        activePorts: Map<Int, Int>,
        containerPorts: Set<Int>,
        dockerPortsInUse: Set<Int>,
        portRange: IntRange,
        rejectedPorts: Set<Int>,
        preferredHostPorts: Map<Int, Int>
    ): Map<Int, Int> = transaction {
        require(containerPorts.all { it in 1..65535 }) { "Container ports must be between 1 and 65535" }
        require(!portRange.isEmpty()) { "Deployment host-port range is empty" }

        // This advisory lock makes selection plus inserts a single allocator operation across instances.
        exec("SELECT pg_advisory_xact_lock(741923608451)")

        val serviceAllocations = DeploymentPortAllocations.selectAll().where {
            (DeploymentPortAllocations.serviceId eq serviceId) and
                (DeploymentPortAllocations.environment eq environment)
        }.toList()
        val activeSlot = serviceAllocations.firstOrNull { row ->
            activePorts[row[DeploymentPortAllocations.containerPort]] == row[DeploymentPortAllocations.hostPort]
        }?.get(DeploymentPortAllocations.slot)
        val candidateSlot = DeploymentHostPortPool.candidateSlot(activeSlot)
        val allReserved = DeploymentPortAllocations.selectAll()
            .map { it[DeploymentPortAllocations.hostPort] }.toMutableSet()
        val slots = mutableMapOf<Int, MutableMap<Int, Int>>()

        // Reserve both blue/green slots up front so every exposed container port has a stable pair.
        // The candidate slot must reuse the active slot's host ports so the gateway,
        // nginx upstream, and Traefik ForwardAuth keep pointing at the same port as the
        // container starts. This avoid the stale-port mismatch that surfaces as a
        // "READY but not detected" cutover (the gateway wants the active port, the
        // candidate published a random port, and reconciliation reports drift).
        for (slot in 0..1) {
            val slotPorts = linkedMapOf<Int, Int>()
            for (containerPort in containerPorts.sorted()) {
                val existing = serviceAllocations.firstOrNull { row ->
                    row[DeploymentPortAllocations.slot] == slot &&
                        row[DeploymentPortAllocations.containerPort] == containerPort
                }
                val oldHostPort = existing?.get(DeploymentPortAllocations.hostPort)
                val isActiveAssignment = slot == activeSlot && oldHostPort != null &&
                    activePorts[containerPort] == oldHostPort
                val mustReallocate = oldHostPort == null || oldHostPort in rejectedPorts ||
                    (oldHostPort in dockerPortsInUse && !isActiveAssignment)
                if (!mustReallocate) {
                    slotPorts[containerPort] = requireNotNull(oldHostPort)
                    continue
                }

                // Candidate slot: reuse the other slot's host port for this container
                // port so the deployed container binds the same host port the gateway
                // expects. Only allocate a truly new port when neither slot has one.
                if (slot == candidateSlot && activeSlot != null) {
                    val reuseCandidate = serviceAllocations.firstOrNull { row ->
                        row[DeploymentPortAllocations.slot] == activeSlot &&
                            row[DeploymentPortAllocations.containerPort] == containerPort
                    }?.get(DeploymentPortAllocations.hostPort)
                    if (reuseCandidate != null && reuseCandidate !in rejectedPorts &&
                        !(reuseCandidate in dockerPortsInUse && !isActiveAssignment)
                    ) {
                        slotPorts[containerPort] = reuseCandidate
                        continue
                    }
                }

                val blocked = dockerPortsInUse + rejectedPorts
                val preferredHostPort = preferredHostPorts[containerPort]
                val hostPort = if (preferredHostPort != null &&
                    preferredHostPort !in blocked &&
                    preferredHostPort !in allReserved &&
                    preferredHostPort in portRange) {
                    preferredHostPort
                } else {
                    DeploymentHostPortPool.firstAvailable(portRange, allReserved, blocked)
                        ?: error("Deployment host-port pool ${portRange.first}-${portRange.last} is exhausted")
                }
                if (existing == null) {
                    DeploymentPortAllocations.insert {
                        it[DeploymentPortAllocations.id] = UUID.randomUUID()
                        it[DeploymentPortAllocations.serviceId] = serviceId
                        it[DeploymentPortAllocations.environment] = environment
                        it[DeploymentPortAllocations.slot] = slot
                        it[DeploymentPortAllocations.containerPort] = containerPort
                        it[DeploymentPortAllocations.hostPort] = hostPort
                    }
                } else {
                    DeploymentPortAllocations.update({ DeploymentPortAllocations.id eq existing[DeploymentPortAllocations.id] }) {
                        it[DeploymentPortAllocations.hostPort] = hostPort
                    }
                    allReserved.remove(requireNotNull(oldHostPort))
                }
                allReserved += hostPort
                slotPorts[containerPort] = hostPort
            }
            slots[slot] = slotPorts
        }
        slots.getValue(candidateSlot)
    }

    fun clearAllocationForService(serviceId: UUID, environment: String) = transaction {
        DeploymentPortAllocations.deleteWhere {
            (DeploymentPortAllocations.serviceId eq serviceId) and
                (DeploymentPortAllocations.environment eq environment)
        }
    }
}
