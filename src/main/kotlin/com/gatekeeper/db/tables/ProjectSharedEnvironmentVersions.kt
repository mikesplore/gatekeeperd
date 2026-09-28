package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Immutable encrypted versions of project-shared environment variables. */
object ProjectSharedEnvironmentVersions : Table("project_shared_environment_versions") {
    val id = uuid("id").autoGenerate()
    val projectId = reference("project_id", Projects.id, onDelete = ReferenceOption.RESTRICT)
    val environment = text("environment")
    val version = integer("version")
    val encryptedPayload = text("encrypted_payload")
    val createdAt = datetime("created_at")
    val createdBy = text("created_by").nullable()

    init {
        uniqueIndex("uq_project_shared_environment_version", projectId, environment, version)
        index("idx_project_shared_environment_versions_owner", false, projectId, environment, version)
    }

    override val primaryKey = PrimaryKey(id)
}
