package com.gatekeeper.db.repositories

import com.gatekeeper.feature.customer.data.persistence.ExposedCustomerRepository
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

@Deprecated("Inject CustomerRepository instead")
object CustomerRepository {
    data class CustomerRecord(val id: UUID, val name: String, val contactEmail: String?, val contactPhone: String?, val billingStatus: String, val createdAt: LocalDateTime, val updatedAt: LocalDateTime)

    data class CustomerSiteRecord(val customer: CustomerRecord, val project: ProjectRepository.ProjectRecord, val site: SiteRepository.SiteRecord?)

    private val delegate: CustomerStore by lazy { ExposedCustomerRepository() }

    fun findAll(): List<CustomerRecord> = delegate.findAll().map { it.toLegacy() }

    fun findPage(query: String?, limit: Int, offset: Int): Pair<List<CustomerRecord>, Int> = delegate.findPage(query, limit, offset).let { (items, total) -> items.map { it.toLegacy() } to total }

    fun findById(id: UUID): CustomerRecord? = delegate.findById(id)?.toLegacy()

    fun existsByContactEmail(email: String): Boolean = delegate.existsByContactEmail(email)

    fun create(name: String, contactEmail: String?, contactPhone: String?, billingStatus: String = "unknown"): CustomerRecord = delegate.create(name, contactEmail, contactPhone, billingStatus).toLegacy()

    fun findSites(id: UUID): List<CustomerSiteRecord> = transaction {
        val customer = findById(id)
            ?: return@transaction emptyList()
        val projects = ProjectRepository.findByCustomerId(id)
        val sites = SiteRepository.findByProjectIds(projects.map { it.id })
        projects.map { project -> CustomerSiteRecord(customer, project, sites[project.id]) }
    }

    private fun CustomerStore.Record.toLegacy() = CustomerRecord(id, name, contactEmail, contactPhone, billingStatus, createdAt, updatedAt)
}
