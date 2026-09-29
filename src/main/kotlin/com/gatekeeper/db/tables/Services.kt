package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table

/** Stable runtime service identity owned by a project. */
object Services : Table("services") {
    val id = uuid("id").autoGenerate()
    val projectId = reference("project_id", Projects.id)
    val name = text("name")
    val isDefault = bool("is_default").default(false)
    val accessStatus = text("access_status").default("active")
    val blockReason = text("block_reason").nullable()
    val blockReasonCode = customEnumeration(
        "block_reason_code",
        "TEXT",
        { value -> AccessBlockReason.entries.first { it.value == (value as String) } },
        { it.value }
    ).nullable()
    val blockReasonNote = text("block_reason_note").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_services_project_name", projectId, name)
    }
}
