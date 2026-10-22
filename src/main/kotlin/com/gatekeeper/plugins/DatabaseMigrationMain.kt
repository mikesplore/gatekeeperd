package com.gatekeeper.plugins

import com.gatekeeper.config.AppSettings

/** Explicit operational entry point: ./gradlew runMigrations. */
fun main(args: Array<String>) {
    val db = AppSettings.fromEnv().db
    if (args.firstOrNull() == "baseline") {
        DatabaseMigrations.baseline(db.url, db.user, db.password, db.migrationBaselineVersion)
    } else {
        DatabaseMigrations.migrate(db.url, db.user, db.password, db.migrationBaselineVersion)
    }
}
