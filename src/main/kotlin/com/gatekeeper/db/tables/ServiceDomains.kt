package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Service-level domain bindings. One row maps a service to a hostname via the
 * container port the domain should route to. `containerPort` is nullable so a
 * domain can be registered before the first deployment publishes a port.
 * `domain` is globally unique: no two bindings may share a hostname.
 */
object ServiceDomains : Table("service_domains") {
    val id = uuid("id").autoGenerate()
    val serviceId = reference("service_id", Services.id, onDelete = ReferenceOption.CASCADE).index()
    val containerPort = integer("container_port").nullable()
    val domain = text("domain").uniqueIndex()
    val status = text("status").default("pending")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

/** Binding lifecycle: pending (port unassigned), bound, port_unavailable (orphaned). */
enum class ServiceDomainStatus(val value: String) {
    PENDING("pending"),
    BOUND("bound"),
    PORT_UNAVAILABLE("port_unavailable");

    companion object {
        fun from(value: String): ServiceDomainStatus =
            entries.first { it.value == value }
    }
}
