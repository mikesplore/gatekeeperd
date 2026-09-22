package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.Customers
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.Sites
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID
import org.jetbrains.exposed.sql.insert

object CustomerRepository {
    data class CustomerRecord(val id: UUID, val name: String, val contactEmail: String?, val contactPhone: String?, val billingStatus: String, val createdAt: LocalDateTime, val updatedAt: LocalDateTime)

    data class CustomerSiteRecord(val customer: CustomerRecord, val project: ProjectRepository.ProjectRecord, val site: SiteRepository.SiteRecord?)

    fun findAll(): List<CustomerRecord> = transaction {
        Customers.selectAll().orderBy(Customers.createdAt, SortOrder.ASC).map { it.toRecord() }
    }

    fun findById(id: UUID): CustomerRecord? = transaction {
        Customers.selectAll().where { Customers.id eq id }.singleOrNull()?.toRecord()
    }

    fun create(name: String, contactEmail: String?, contactPhone: String?, billingStatus: String = "unknown"): CustomerRecord = transaction {
        val id = UUID.randomUUID()
        Customers.insert {
            it[Customers.id] = id
            it[Customers.name] = name
            it[Customers.contactEmail] = contactEmail
            it[Customers.contactPhone] = contactPhone
            it[Customers.billingStatus] = billingStatus
        }
        findById(id)!!
    }

    fun findSites(id: UUID): List<CustomerSiteRecord> = transaction {
        val customer = Customers.selectAll().where { Customers.id eq id }.singleOrNull()?.toRecord()
            ?: return@transaction emptyList()
        val projects = ProjectRepository.findByCustomerId(id)
        val sites = SiteRepository.findByProjectIds(projects.map { it.id })
        projects.map { project -> CustomerSiteRecord(customer, project, sites[project.id]) }
    }

    private fun ResultRow.toRecord() = CustomerRecord(
        this[Customers.id], this[Customers.name], this[Customers.contactEmail], this[Customers.contactPhone],
        this[Customers.billingStatus], this[Customers.createdAt], this[Customers.updatedAt]
    )
}
