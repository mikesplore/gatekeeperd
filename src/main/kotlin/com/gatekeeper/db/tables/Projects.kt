package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.date

object Projects : Table("projects") {
    val id = uuid("id").autoGenerate()
    val slug = text("slug").uniqueIndex()
    val name = text("name")
    val domain = text("domain")
    val containerName = text("container_name")
    val type = enumerationByName("type", 9, ProjectType::class)
    val status = enumerationByName("status", 12, ProjectStatus::class).default(ProjectStatus.ACTIVE)
    val clientName = text("client_name").nullable()
    val clientEmail = text("client_email").nullable()
    val paystackCustomerCode = text("paystack_customer_code").nullable()
    val amountDue = decimal("amount_due", 12, 2).nullable()
    val currency = text("currency").default("NGN")
    val dueDate = date("due_date").nullable()
    val gracePeriodDays = integer("grace_period_days").default(3)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

enum class ProjectType(val value: String) {
    FRONTEND("frontend"),
    BACKEND("backend");
}

enum class ProjectStatus(val value: String) {
    ACTIVE("active"),
    BLOCKED("blocked"),
    MANUAL_BLOCK("manual_block");
}