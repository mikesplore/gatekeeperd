package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.DeploymentPortAllocations
import com.gatekeeper.deployment.DeploymentHostPortPool
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object DeploymentPortAllocationRepository {
    /** Reserves/reuses both slots atomically; the DB lock coordinates all Gatekeeperd instances. */
    fun allocateSlot(
        serviceId: UUID,
        environment: String,
        activePorts: Map<Int, Int>,
        containerPorts: Set<Int>,
        dockerPortsInUse: Set<Int>,
        portRange: IntRange,
        rejectedPorts: Set<Int> = emptySet()
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

                val blocked = dockerPortsInUse + rejectedPorts
                val hostPort = DeploymentHostPortPool.firstAvailable(portRange, allReserved, blocked)
                    ?: error("Deployment host-port pool ${portRange.first}-${portRange.last} is exhausted")
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
}
