package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

/** Durable deployment intent. Secret values are encrypted and are never serialized by API responses. */
object DeploymentConfigurations : Table("deployment_configurations") {
    val id = uuid("id")
    val repository = text("repository").nullable()
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
    val autoDeploy = bool("auto_deploy").default(false)
    val projectId = uuid("project_id")
    val environment = text("environment").default("production")
    val readinessType: Column<String?> = text("readiness_type").nullable()
    val readinessTarget: Column<String?> = text("readiness_target").nullable()
    val readinessTimeoutSeconds: Column<Int> = integer("readiness_timeout_seconds").default(60)
    val readinessIntervalSeconds: Column<Int> = integer("readiness_interval_seconds").default(2)
    val readinessProbeTimeoutMillis: Column<Int> = integer("readiness_probe_timeout_millis").default(1000)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
