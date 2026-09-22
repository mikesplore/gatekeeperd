package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.date

object Projects : Table("projects") {
    val id = uuid("id").autoGenerate()
    val customerId = reference("customer_id", Customers.id).nullable().index()
    val slug = text("slug").uniqueIndex()
    val name = text("name")
    val domain = text("domain")
    val containerName = text("container_name")
    val type = enumerationByName("type", 9, ProjectType::class)
    val status = customEnumeration(
        "status",
        "TEXT",
        { value -> ProjectStatus.entries.first { it.value == (value as String) } },
        { it.value }
    ).default(ProjectStatus.ACTIVE)
    val blockReason = text("block_reason").nullable()
    val deploymentMode = text("deployment_mode").default("developer_hosted")
    val serviceMode = text("service_mode").default("development")
    val lifecycleStatus = text("lifecycle_status").default("active")
    val billingName = text("billing_name").nullable()
    val billingEmail = text("billing_email").nullable()
    val billingAddress = text("billing_address").nullable()
    val paystackCustomerCode = text("paystack_customer_code").nullable()
    val amountDue = decimal("amount_due", 12, 2).nullable()
    val baseAmount = decimal("base_amount", 12, 2).nullable()
    val currency = text("currency").default("KES")
    val dueDate = date("due_date").nullable()
    val gracePeriodDays = integer("grace_period_days").default(3)
    val deletedAt = datetime("deleted_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    val githubRepository = text("github_repository").nullable()
    val githubRef = text("github_ref").default("main")
    val deployImageName = text("deploy_image_name").nullable()
    val deployImageTag = text("deploy_image_tag").default("latest")
    val autoDeploy = bool("auto_deploy").default(false)

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
