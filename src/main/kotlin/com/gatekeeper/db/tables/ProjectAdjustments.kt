package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

object ProjectAdjustments : Table("project_adjustments") {
    val id = uuid("id").autoGenerate()
    val projectId = uuid("project_id").references(Projects.id)
    val type = enumerationByName("type", 17, AdjustmentType::class)
    val amount = decimal("amount", 12, 2)
    val reason = text("reason")
    val actor = text("actor")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

enum class AdjustmentType {
    ADDITIONAL_CHARGE,
    DISCOUNT
}
