package com.gatekeeper.db.repositories

import java.sql.Connection
import java.sql.DriverManager

/** Read-only inventory of deployment slug ownership before the project_id backfill. */
object DeploymentOwnershipReport {
    private const val BATCH_SIZE = 500
    private val deploymentTables = listOf("deployment_configurations", "deployment_executions", "deployment_jobs")

    private data class Counts(
        var uniquelyResolved: Long = 0,
        var nullSlug: Long = 0,
        var slugNotFound: Long = 0,
        var archivedProject: Long = 0,
        var ambiguous: Long = 0
    )

    fun run(url: String, user: String, password: String, applyBackfill: Boolean = false) {
        DriverManager.getConnection(url, user, password).use { connection ->
            if (applyBackfill) {
                connection.autoCommit = false
                connection.isReadOnly = false
                deploymentTables.forEach { table ->
                    val updated = backfillTable(connection, table)
                    println("$table: backfilled_project_id=$updated")
                }
                connection.commit()
            } else {
                connection.autoCommit = false
                connection.isReadOnly = true
            }
            try {
                deploymentTables.forEach { table ->
                    reportTable(connection, table)
                    if (applyBackfill) logUnresolvedRows(connection, table)
                }
            }
            finally { connection.rollback() }
        }
    }

    private fun backfillTable(connection: Connection, table: String): Long {
        val sql = """
            WITH batch AS (
                SELECT d.id, p.id AS project_id
                FROM $table d
                JOIN projects p ON p.slug = d.project_slug AND p.deleted_at IS NULL
                WHERE d.project_id IS NULL
                  AND d.project_slug IS NOT NULL
                  AND (SELECT COUNT(*) FROM projects p2 WHERE p2.slug = d.project_slug) = 1
                ORDER BY d.id
                LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
            ), updated AS (
                UPDATE $table d
                SET project_id = batch.project_id
                FROM batch
                WHERE d.id = batch.id AND d.project_id IS NULL
                RETURNING d.id
            )
            SELECT COUNT(*) FROM updated
        """.trimIndent()
        var total = 0L
        while (true) {
            val count = connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, BATCH_SIZE)
                statement.executeQuery().use { result ->
                    check(result.next()) { "No backfill result returned for $table" }
                    result.getLong(1)
                }
            }
            if (count == 0L) break
            connection.commit()
            total += count
        }
        return total
    }

    private fun reportTable(connection: Connection, table: String) {
        val sql = """
            SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE d.project_slug IS NULL) AS null_slug,
                COUNT(*) FILTER (WHERE d.project_slug IS NOT NULL AND p.slug IS NULL) AS slug_not_found,
                COUNT(*) FILTER (WHERE p.slug IS NOT NULL AND p.match_count > 1) AS ambiguous,
                COUNT(*) FILTER (WHERE p.match_count = 1 AND p.deleted_at IS NOT NULL) AS archived_project,
                COUNT(*) FILTER (WHERE p.match_count = 1 AND p.deleted_at IS NULL) AS uniquely_resolved
            FROM $table d
            LEFT JOIN (
                SELECT slug, COUNT(*) AS match_count, MAX(deleted_at) AS deleted_at
                FROM projects
                GROUP BY slug
            ) p ON p.slug = d.project_slug
        """.trimIndent()
        val counts = connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next()) { "No aggregate result returned for $table" }
                Counts(
                    uniquelyResolved = result.getLong("uniquely_resolved"),
                    nullSlug = result.getLong("null_slug"),
                    slugNotFound = result.getLong("slug_not_found"),
                    archivedProject = result.getLong("archived_project"),
                    ambiguous = result.getLong("ambiguous")
                ) to result.getLong("total")
            }
        }
        println(
            "$table: total=${counts.second}, uniquely_resolved=${counts.first.uniquelyResolved}, " +
                "null_slug=${counts.first.nullSlug}, slug_not_found=${counts.first.slugNotFound}, " +
                "slug_maps_to_archived_project=${counts.first.archivedProject}, ambiguous=${counts.first.ambiguous}"
        )
    }

    private fun logUnresolvedRows(connection: Connection, table: String) {
        val sql = """
            SELECT d.id, d.project_slug,
                CASE
                    WHEN d.project_slug IS NULL THEN 'null_slug'
                    WHEN p.match_count IS NULL THEN 'slug_not_found'
                    WHEN p.match_count > 1 THEN 'ambiguous'
                    WHEN p.deleted_at IS NOT NULL THEN 'slug_maps_to_archived_project'
                    ELSE 'project_id_missing'
                END AS bucket
            FROM $table d
            LEFT JOIN (
                SELECT slug, COUNT(*) AS match_count, MAX(deleted_at) AS deleted_at
                FROM projects GROUP BY slug
            ) p ON p.slug = d.project_slug
            WHERE d.project_id IS NULL
            ORDER BY d.id
        """.trimIndent()
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                while (result.next()) {
                    val rowId = result.getObject("id")
                    val slug = result.getString("project_slug") ?: "<null>"
                    val bucket = result.getString("bucket")
                    println("$table unresolved: id=$rowId bucket=$bucket project_slug=$slug")
                }
            }
        }
    }
}
