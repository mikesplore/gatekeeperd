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
        ,Migration("4", "integration outbox", "db/migration/V4__integration_outbox.sql")
        ,Migration("5", "outbox claims and dead letters", "db/migration/V5__outbox_claims_and_dead_letters.sql")
        ,Migration("6", "password reset tokens", "db/migration/V6__password_reset_tokens.sql")
        ,Migration("7", "deployment jobs", "db/migration/V7__deployment_jobs.sql")
        ,Migration("8", "deployment container options", "db/migration/V8__deployment_container_options.sql")
        ,Migration("9", "github deployment mapping", "db/migration/V9__github_deployment_mapping.sql")
        ,Migration("10", "deployment reliability", "db/migration/V10__deployment_reliability.sql")
        ,Migration("11", "github app installation", "db/migration/V11__github_app_installation.sql")
        ,Migration("12", "github installation state", "db/migration/V12__github_installation_state.sql")
        ,Migration("13", "deployment runtime spec", "db/migration/V13__deployment_runtime_spec.sql")
        ,Migration("14", "encrypted deployment secrets", "db/migration/V14__encrypted_deployment_secrets.sql")
        ,Migration("15", "notifications", "db/migration/V15__notifications.sql")
        ,Migration("16", "registry credentials", "db/migration/V16__registry_credentials.sql")
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
