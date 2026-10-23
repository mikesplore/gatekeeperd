package com.gatekeeper.feature.deployment.domain.repository

import java.util.UUID

interface ServiceDomainRepository {
    data class Binding(
        val id: UUID,
        val serviceId: UUID,
        val containerPort: Int?,
        val domain: String,
        val status: String,
    )

    fun listByService(serviceId: UUID): List<Binding>
    fun findByServiceAndDomain(serviceId: UUID, domain: String): Binding?
    fun findByDomain(domain: String): Binding?
    fun findByServiceAndPort(serviceId: UUID, containerPort: Int): Binding?
    fun findById(serviceId: UUID, id: UUID): Binding?
    fun add(serviceId: UUID, domain: String, containerPort: Int?): Binding
    fun attachPort(id: UUID, containerPort: Int): Boolean
    fun update(id: UUID, serviceId: UUID, domain: String, containerPort: Int?): Binding?
    fun markPortUnavailable(id: UUID): Boolean
    fun remove(serviceId: UUID, domain: String): Boolean
    fun removeById(serviceId: UUID, id: UUID): Boolean
    fun reconcilePorts(serviceId: UUID, publishedContainerPorts: Set<Int>): List<Binding>
    fun unboundPorts(serviceId: UUID, publishedContainerPorts: Set<Int>): List<Int>
}
