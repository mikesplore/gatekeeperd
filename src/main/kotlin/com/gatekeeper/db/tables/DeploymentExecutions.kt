package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

/** Immutable execution snapshot plus mutable worker output. */
object DeploymentExecutions : Table("deployment_executions") {
    val id = uuid("id")
    val configurationId = uuid("configuration_id").references(DeploymentConfigurations.id, onDelete = ReferenceOption.RESTRICT)
    val repository = text("repository")
    val gitRef = text("git_ref")
    val registry = text("registry")
    val imageName = text("image_name")
    val imageTag = text("image_tag")
    val hostPort = integer("host_port").nullable()
    val containerPort = integer("container_port").nullable()
    val network = text("network")
    val restartPolicy = text("restart_policy")
    val envJson = text("env_json")
    val secretEnvEncrypted = text("secret_env_encrypted").nullable()
    val secretSetId = uuid("secret_set_id").nullable()
    val secretSetVersion = integer("secret_set_version").nullable()
    val volumesJson = text("volumes_json")
    val createNetworkIfMissing = bool("create_network_if_missing")
    val projectId = uuid("project_id")
    val environment = text("environment").default("production")
    val readinessType = text("readiness_type").nullable()
    val readinessTarget = text("readiness_target").nullable()
    val readinessTimeoutSeconds = integer("readiness_timeout_seconds").default(60)
    val readinessIntervalSeconds = integer("readiness_interval_seconds").default(2)
    val readinessProbeTimeoutMillis = integer("readiness_probe_timeout_millis").default(1000)
    val triggerSource = text("trigger_source")
    val status = text("status")
    val currentStep = text("current_step")
    val logs = text("logs")
    val commitSha = text("commit_sha").nullable()
    val imageDigest = text("image_digest").nullable()
    val errorMessage = text("error_message").nullable()
    val cancelledAt = datetime("cancelled_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val startedAt = datetime("started_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
