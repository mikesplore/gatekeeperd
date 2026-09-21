package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

/** Durable deployment intent. Secret values are encrypted and are never serialized by API responses. */
object DeploymentConfigurations : Table("deployment_configurations") {
    val id = uuid("id")
    val repository = text("repository")
    val gitRef = text("git_ref")
    val registry = text("registry")
    val imageName = text("image_name")
    val imageTag = text("image_tag")
    val containerName = text("container_name").nullable()
    val hostPort = integer("host_port").nullable()
    val containerPort = integer("container_port").nullable()
    val network = text("network")
    val restartPolicy = text("restart_policy")
    val envJson = text("env_json")
    val secretEnvEncrypted = text("secret_env_encrypted").nullable()
    val volumesJson = text("volumes_json")
    val createNetworkIfMissing = bool("create_network_if_missing")
    val projectSlug = text("project_slug").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
