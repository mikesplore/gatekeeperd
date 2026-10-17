package com.gatekeeper.feature.customer.data.persistence

import com.gatekeeper.db.tables.Customers
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedCustomerRepository : CustomerRepository {
    override fun findAll(): List<CustomerRepository.Record> = transaction {
        Customers.selectAll().orderBy(Customers.createdAt, SortOrder.ASC).map { it.toRecord() }
    }

    override fun findPage(query: String?, limit: Int, offset: Int): Pair<List<CustomerRepository.Record>, Int> = transaction {
        val normalized = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val rows = if (normalized == null) Customers.selectAll() else Customers.selectAll().where {
            (Customers.name.lowerCase() like "%$normalized%") or
                (Customers.contactEmail.lowerCase() like "%$normalized%") or
                (Customers.contactPhone.lowerCase() like "%$normalized%")
        }
        val all = rows.orderBy(Customers.createdAt, SortOrder.ASC).map { it.toRecord() }
        all.drop(offset).take(limit) to all.size
    }

    override fun findById(id: UUID): CustomerRepository.Record? = transaction {
        Customers.selectAll().where { Customers.id eq id }.singleOrNull()?.toRecord()
    }

    override fun existsByContactEmail(email: String): Boolean = transaction {
        val normalized = email.trim().lowercase()
        normalized.isNotBlank() && Customers.selectAll().where {
            Customers.contactEmail.lowerCase() eq normalized
        }.singleOrNull() != null
    }

    override fun create(name: String, contactEmail: String?, contactPhone: String?, billingStatus: String): CustomerRepository.Record = transaction {
        val id = UUID.randomUUID()
        Customers.insert {
            it[Customers.id] = id
            it[Customers.name] = name
            it[Customers.contactEmail] = contactEmail
            it[Customers.contactPhone] = contactPhone
            it[Customers.billingStatus] = billingStatus
        }
        Customers.selectAll().where { Customers.id eq id }.single().toRecord()
    }

    private fun ResultRow.toRecord() = CustomerRepository.Record(
        this[Customers.id], this[Customers.name], this[Customers.contactEmail], this[Customers.contactPhone],
        this[Customers.billingStatus], this[Customers.createdAt], this[Customers.updatedAt]
    )
}
