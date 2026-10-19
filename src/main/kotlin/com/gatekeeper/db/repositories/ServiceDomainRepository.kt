package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.ServiceDomainStatus
import com.gatekeeper.db.tables.ServiceDomains
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

object ServiceDomainRepository {

    data class Binding(
        val id: UUID,
        val serviceId: UUID,
        val containerPort: Int?,
        val domain: String,
        val status: String,
    )

    fun listByService(serviceId: UUID): List<Binding> = transaction {
        ServiceDomains.selectAll()
            .where { ServiceDomains.serviceId eq serviceId }
            .orderBy(ServiceDomains.createdAt, SortOrder.ASC)
            .map { it.toBinding() }
    }

    fun findByServiceAndDomain(serviceId: UUID, domain: String): Binding? = transaction {
        ServiceDomains.selectAll()
            .where { (ServiceDomains.serviceId eq serviceId) and (ServiceDomains.domain eq domain) }
            .singleOrNull()?.toBinding()
    }

    fun findByDomain(domain: String): Binding? = transaction {
        ServiceDomains.selectAll()
            .where { ServiceDomains.domain eq domain }
            .singleOrNull()?.toBinding()
    }

    fun findByServiceAndPort(serviceId: UUID, containerPort: Int): Binding? = transaction {
        ServiceDomains.selectAll()
            .where { (ServiceDomains.serviceId eq serviceId) and (ServiceDomains.containerPort eq containerPort) }
            .singleOrNull()?.toBinding()
    }

    fun findById(serviceId: UUID, id: UUID): Binding? = transaction {
        ServiceDomains.selectAll()
            .where { (ServiceDomains.serviceId eq serviceId) and (ServiceDomains.id eq id) }
            .singleOrNull()?.toBinding()
    }

    fun add(serviceId: UUID, domain: String, containerPort: Int?): Binding = transaction {
        val id = UUID.randomUUID()
        val now = LocalDateTime.now()
        ServiceDomains.insert {
            it[ServiceDomains.id] = id
            it[ServiceDomains.serviceId] = serviceId
            it[ServiceDomains.containerPort] = containerPort
            it[ServiceDomains.domain] = domain
            it[ServiceDomains.status] = if (containerPort == null) {
                ServiceDomainStatus.PENDING.value
            } else {
                ServiceDomainStatus.BOUND.value
            }
            it[ServiceDomains.createdAt] = now
            it[ServiceDomains.updatedAt] = now
        }
        val status = if (containerPort == null) {
            ServiceDomainStatus.PENDING.value
        } else {
            ServiceDomainStatus.BOUND.value
        }
        Binding(id, serviceId, containerPort, domain, status)
    }

    fun attachPort(id: UUID, containerPort: Int): Boolean = transaction {
        val updated = ServiceDomains.update({ ServiceDomains.id eq id }) {
            it[ServiceDomains.containerPort] = containerPort
            it[ServiceDomains.status] = ServiceDomainStatus.BOUND.value
            it[ServiceDomains.updatedAt] = LocalDateTime.now()
        }

        updated > 0
    }

    fun update(id: UUID, serviceId: UUID, domain: String, containerPort: Int?): Binding? = transaction {
        val updated = ServiceDomains.update({
            (ServiceDomains.id eq id) and (ServiceDomains.serviceId eq serviceId)
        }) {
            it[ServiceDomains.domain] = domain
            it[ServiceDomains.containerPort] = containerPort
            it[ServiceDomains.status] = if (containerPort == null) ServiceDomainStatus.PENDING.value else ServiceDomainStatus.BOUND.value
            it[ServiceDomains.updatedAt] = LocalDateTime.now()
        }
        if (updated == 0) return@transaction null
        findById(serviceId, id)
    }

    fun markPortUnavailable(id: UUID): Boolean = transaction {
        val updated = ServiceDomains.update({ ServiceDomains.id eq id }) {
            it[ServiceDomains.status] = ServiceDomainStatus.PORT_UNAVAILABLE.value
            it[ServiceDomains.updatedAt] = LocalDateTime.now()
        }
        updated > 0
    }

    fun remove(serviceId: UUID, domain: String): Boolean = transaction {
        val deleted = ServiceDomains.deleteWhere {
            (ServiceDomains.serviceId eq serviceId) and (ServiceDomains.domain eq domain)
        }

        deleted > 0
    }

    fun removeById(serviceId: UUID, id: UUID): Boolean = transaction {
        val deleted = ServiceDomains.deleteWhere {
            (ServiceDomains.serviceId eq serviceId) and (ServiceDomains.id eq id)
        }
        deleted > 0
    }

    fun reconcilePorts(serviceId: UUID, publishedContainerPorts: Set<Int>): List<Binding> = transaction {
        if (publishedContainerPorts.isEmpty()) {
            listByService(serviceId)
                .filter { it.containerPort != null && it.status == ServiceDomainStatus.BOUND.value }
                .forEach { markPortUnavailable(it.id) }
            return@transaction listByService(serviceId)
        }
        if (publishedContainerPorts.size == 1) {
            val sole = publishedContainerPorts.single()
            listByService(serviceId).filter { it.containerPort == null }.forEach { attachPort(it.id, sole) }
        }
        listByService(serviceId).forEach { binding ->
            val port = binding.containerPort ?: return@forEach
            if (port !in publishedContainerPorts && binding.status == ServiceDomainStatus.BOUND.value) {
                markPortUnavailable(binding.id)
            } else if (port in publishedContainerPorts && binding.status == ServiceDomainStatus.PORT_UNAVAILABLE.value) {
                attachPort(binding.id, port)
            }
        }
        listByService(serviceId)
    }

    fun unboundPorts(serviceId: UUID, publishedContainerPorts: Set<Int>): List<Int> = transaction {
        val bound = ServiceDomains.selectAll()
            .where {
                (ServiceDomains.serviceId eq serviceId) and
                    (ServiceDomains.status eq ServiceDomainStatus.BOUND.value)
            }
            .mapNotNull { it[ServiceDomains.containerPort] }
            .toSet()
        (publishedContainerPorts - bound).sorted()
    }

    private fun ResultRow.toBinding() = Binding(
        id = this[ServiceDomains.id],
        serviceId = this[ServiceDomains.serviceId],
        containerPort = this[ServiceDomains.containerPort],
        domain = this[ServiceDomains.domain],
        status = this[ServiceDomains.status],
    )
}
