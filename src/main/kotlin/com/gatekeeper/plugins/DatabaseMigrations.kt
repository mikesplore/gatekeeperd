package com.gatekeeper.plugins

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

object DatabaseMigrations {
    private val logger = LoggerFactory.getLogger(DatabaseMigrations::class.java)
    fun migrate(url: String, user: String, password: String, baselineVersion: String = "0") {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
            .baselineOnMigrate(true).baselineVersion(baselineVersion).load().migrate()
        logger.info("Database migrations completed")
    }

    fun baseline(url: String, user: String, password: String, version: String) {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
            .baselineVersion(version).load().baseline()
        logger.info("Database baseline created at version {}", version)
    }

    fun validate(url: String, user: String, password: String) {
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
            .baselineOnMigrate(true).baselineVersion("0").load().validate()
        logger.info("Database migration validation completed")
    }
}
