package com.gatekeeper.plugins

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory

private val migrationLogger = LoggerFactory.getLogger("com.gatekeeper.plugins.DatabaseMigrations")

object DatabaseMigrations {
    private data class Migration(val version: String, val description: String, val resource: String)

    private val migrations = listOf(
        Migration("1", "phase 2 state fields", "db/migration/V1__phase2_state_fields.sql"),
        Migration("2", "phase 5 customer workflows", "db/migration/V2__phase_5_customer_workflows.sql")
        ,Migration("3", "provider neutral payments", "db/migration/V3__provider_neutral_payments.sql")
    )

    fun apply(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val hasProjectsTable = connection.prepareStatement("SELECT to_regclass('public.projects')")
                .use { statement ->
                    statement.executeQuery().use { result -> result.next() && result.getString(1) != null }
                }

            if (!hasProjectsTable) {
                val baseline = DatabaseMigrations::class.java.classLoader
                    .getResourceAsStream("db/migration/V0__baseline_schema.sql")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("Missing database baseline migration resource")
                connection.createStatement().use { statement -> statement.execute(baseline) }
            }

            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version TEXT PRIMARY KEY,
                        description TEXT NOT NULL,
                        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent()
                )
            }

            if (!hasProjectsTable) {
                connection.prepareStatement(
                    "INSERT INTO schema_migrations(version, description) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, "0")
                    statement.setString(2, "baseline schema")
                    statement.executeUpdate()
                }
            }

            migrations.forEach { migration ->
                val applied = connection.prepareStatement(
                    "SELECT 1 FROM schema_migrations WHERE version = ?"
                ).use { statement ->
                    statement.setString(1, migration.version)
                    statement.executeQuery().use { result -> result.next() }
                }
                if (applied) return@forEach

                val sql = DatabaseMigrations::class.java.classLoader
                    .getResourceAsStream(migration.resource)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("Missing database migration resource: ${migration.resource}")

                connection.createStatement().use { statement -> statement.execute(sql) }
                connection.prepareStatement(
                    "INSERT INTO schema_migrations(version, description) VALUES (?, ?)"
                ).use { statement ->
                    statement.setString(1, migration.version)
                    statement.setString(2, migration.description)
                    statement.executeUpdate()
                }
                migrationLogger.info("Applied database migration V${migration.version}__${migration.description}")
            }
            connection.commit()
        }
    }
}
