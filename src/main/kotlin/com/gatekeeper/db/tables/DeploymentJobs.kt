package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.CurrentDateTime

object DeploymentJobs : Table("deployment_jobs") {
    val id = uuid("id")
    val repository = text("repository")
    val gitRef = text("git_ref")
    val registry = text("registry")
    val imageName = text("image_name")
    val imageTag = text("image_tag")
    val containerName = text("container_name").nullable()
    val hostPort = integer("host_port").nullable()
    val containerPort = integer("container_port").nullable()
    val network = text("network").default("bridge")
    val restartPolicy = text("restart_policy").default("unless-stopped")
    val envJson = text("env_json").default("{}")
    val secretEnvEncrypted = text("secret_env_encrypted").nullable()
    val volumesJson = text("volumes_json").default("[]")
    val createNetworkIfMissing = bool("create_network_if_missing").default(false)
    val status = text("status")
    val currentStep = text("current_step")
    val logs = text("logs").default("")
    val commitSha = text("commit_sha").nullable()
    val imageDigest = text("image_digest").nullable()
    val previousContainerName = text("previous_container_name").nullable()
    val previousImage = text("previous_image").nullable()
    val cancelledAt = datetime("cancelled_at").nullable()
    val projectSlug = text("project_slug").nullable()
    val triggerSource = text("trigger_source").default("manual")
    val errorMessage = text("error_message").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val startedAt = datetime("started_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
