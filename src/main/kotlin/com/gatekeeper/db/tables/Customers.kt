package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object Customers : Table("customers") {
    val id = uuid("id").autoGenerate()
    val name = text("name")
    val contactEmail = text("contact_email").nullable()
    val contactPhone = text("contact_phone").nullable()
    val billingStatus = text("billing_status").default("unknown")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
