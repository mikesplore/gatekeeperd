package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

object ServiceInvoices : Table("service_invoices") {
    val serviceId = uuid("service_id").references(Services.id)
    val projectId = uuid("project_id").references(Projects.id)
    val description = text("description")
    val amount = decimal("amount", 12, 2)
    val currency = varchar("currency", 3)
    val dueDate = date("due_date").nullable()
    val openingPaid = decimal("opening_paid", 12, 2).default(java.math.BigDecimal.ZERO)
    val scribedInvoiceId = text("scribed_invoice_id").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
    override val primaryKey = PrimaryKey(serviceId)
}
