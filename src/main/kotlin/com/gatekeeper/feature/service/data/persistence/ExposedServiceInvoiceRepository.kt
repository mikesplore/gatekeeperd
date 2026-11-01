package com.gatekeeper.feature.service.data.persistence

import com.gatekeeper.db.tables.ServiceInvoices
import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceRepository
import java.time.LocalDateTime
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class ExposedServiceInvoiceRepository : ServiceInvoiceRepository {
    override fun findByServiceId(serviceId: UUID): ServiceInvoiceRepository.Record? = transaction {
        ServiceInvoices.selectAll().where { ServiceInvoices.serviceId eq serviceId }.singleOrNull()?.record()
    }

    override fun listByProjectId(projectId: UUID): List<ServiceInvoiceRepository.Record> = transaction {
        ServiceInvoices.selectAll().where { ServiceInvoices.projectId eq projectId }.map { it.record() }
    }

    override fun create(record: ServiceInvoiceRepository.Record): ServiceInvoiceRepository.Record = transaction {
        ServiceInvoices.insertIgnore {
            it[serviceId] = record.serviceId
            it[projectId] = record.projectId
            it[description] = record.description
            it[amount] = record.amount
            it[currency] = record.currency
            it[dueDate] = record.dueDate
            it[openingPaid] = record.openingPaid
            it[scribedInvoiceId] = record.scribedInvoiceId
            it[createdAt] = record.createdAt
            it[updatedAt] = LocalDateTime.now()
        }
        ServiceInvoices.selectAll().where { ServiceInvoices.serviceId eq record.serviceId }.single().record()
    }

    private fun ResultRow.record() = ServiceInvoiceRepository.Record(
        serviceId = this[ServiceInvoices.serviceId], projectId = this[ServiceInvoices.projectId],
        description = this[ServiceInvoices.description], amount = this[ServiceInvoices.amount],
        currency = this[ServiceInvoices.currency], dueDate = this[ServiceInvoices.dueDate],
        openingPaid = this[ServiceInvoices.openingPaid], scribedInvoiceId = this[ServiceInvoices.scribedInvoiceId],
        createdAt = this[ServiceInvoices.createdAt]
    )
}
