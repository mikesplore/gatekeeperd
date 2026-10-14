package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/** Persistent blue/green host-port assignments for each service environment. */
object DeploymentPortAllocations : Table("deployment_port_allocations") {
    val id = uuid("id")
    val serviceId = uuid("service_id").references(Services.id, onDelete = ReferenceOption.CASCADE)
    val environment = text("environment")
    val slot = integer("slot")
    val containerPort = integer("container_port")
    val hostPort = integer("host_port").uniqueIndex()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_deployment_port_slot_container", serviceId, environment, slot, containerPort)
    }
}
