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
    val status = text("status")
    val currentStep = text("current_step")
    val logs = text("logs").default("")
    val commitSha = text("commit_sha").nullable()
    val imageDigest = text("image_digest").nullable()
    val errorMessage = text("error_message").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val startedAt = datetime("started_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
