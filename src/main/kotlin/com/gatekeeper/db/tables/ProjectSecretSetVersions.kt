package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

/** Immutable encrypted environment map versions owned by a project/environment. */
object ProjectSecretSetVersions : Table("project_secret_set_versions") {
    val id = uuid("id")
    val projectId = uuid("project_id").references(Projects.id, onDelete = ReferenceOption.RESTRICT)
    val serviceId = uuid("service_id").references(Services.id, onDelete = ReferenceOption.RESTRICT).nullable()
    val environment = text("environment")
    val version = integer("version")
    val encryptedPayload = text("encrypted_payload")
    val createdAt = datetime("created_at")
    val createdBy = text("created_by").nullable()

    init {
        uniqueIndex("uq_service_secret_set_version", serviceId, environment, version)
        index("idx_project_secret_set_versions_owner", false, projectId, environment)
        index("idx_project_secret_set_versions_service_owner", false, serviceId, environment)
    }

    override val primaryKey = PrimaryKey(id)
}
