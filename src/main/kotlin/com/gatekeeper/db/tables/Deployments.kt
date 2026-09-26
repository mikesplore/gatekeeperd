package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/** Canonical lifecycle record for one attempted runtime release. */
object Deployments : Table("deployments") {
    val id = uuid("id")
    val projectId = uuid("project_id").references(Projects.id, onDelete = ReferenceOption.RESTRICT).nullable()
    val environment = text("environment").default("production")
    val configurationId = uuid("configuration_id").references(DeploymentConfigurations.id, onDelete = ReferenceOption.RESTRICT)
    val executionId = uuid("execution_id").uniqueIndex().references(DeploymentExecutions.id, onDelete = ReferenceOption.RESTRICT)
    val status = customEnumeration(
        "status", "TEXT",
        { value -> DeploymentStatus.entries.first { it.value == value as String } },
        { it.value }
    )
    val runtimeContainerName = text("runtime_container_name").nullable()
    val runtimeHostPort = integer("runtime_host_port").nullable()
    val runtimePortsJson = text("runtime_ports_json").default("{}")
    val triggerSource = text("trigger_source")
    // Self-referential constraints are installed by the Flyway migration.
    val replacesDeploymentId = uuid("replaces_deployment_id").nullable()
    val rolledBackToDeploymentId = uuid("rolled_back_to_deployment_id").nullable()
    val failureReason = text("failure_reason").nullable()
    val queuedAt = datetime("queued_at").nullable()
    val buildingAt = datetime("building_at").nullable()
    val startingAt = datetime("starting_at").nullable()
    val healthCheckingAt = datetime("health_checking_at").nullable()
    val activeAt = datetime("active_at").nullable()
    val supersededAt = datetime("superseded_at").nullable()
    val failedAt = datetime("failed_at").nullable()
    val cancelledAt = datetime("cancelled_at").nullable()
    val rolledBackAt = datetime("rolled_back_at").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}

enum class DeploymentStatus(val value: String) {
    QUEUED("queued"), BUILDING("building"), STARTING("starting"),
    HEALTH_CHECKING("health-checking"), ACTIVE("active"), SUPERSEDED("superseded"),
    FAILED("failed"), CANCELLED("cancelled"), ROLLED_BACK("rolled-back")
}
