package com.gatekeeper.db

import java.sql.DriverManager

/** Read-only preview of the default-service rows that V4 will create. */
object ServiceIdentityBackfill {
    data class Report(
        val projectsWithoutDefaultService: Long,
        val projectsWithDefaultService: Long
    )

    fun report(url: String, user: String, password: String): Report =
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.isReadOnly = true
            val servicesTableExists = connection.metaData.getTables(null, null, "services", null).use {
                it.next()
            }
            val query = if (servicesTableExists) {
                """
                SELECT
                    count(*) FILTER (WHERE s.project_id IS NULL) AS missing,
                    count(*) FILTER (WHERE s.project_id IS NOT NULL) AS present
                FROM projects p
                LEFT JOIN services s ON s.project_id = p.id AND s.name = 'default'
                """.trimIndent()
            } else {
                "SELECT count(*) AS missing, 0 AS present FROM projects"
            }
            connection.prepareStatement(query).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "Could not produce service identity backfill report" }
                    Report(
                        projectsWithoutDefaultService = rows.getLong("missing"),
                        projectsWithDefaultService = rows.getLong("present")
                    )
                }
            }
        }
}
