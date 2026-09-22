package com.gatekeeper.plugins

import com.gatekeeper.config.AppConfig

/** Explicit operational entry point: ./gradlew runMigrations. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "baseline") {
        DatabaseMigrations.baseline(AppConfig.dbUrl, AppConfig.dbUser, AppConfig.dbPassword, AppConfig.dbMigrationBaselineVersion)
    } else {
        DatabaseMigrations.migrate(AppConfig.dbUrl, AppConfig.dbUser, AppConfig.dbPassword, AppConfig.dbMigrationBaselineVersion)
    }
}
