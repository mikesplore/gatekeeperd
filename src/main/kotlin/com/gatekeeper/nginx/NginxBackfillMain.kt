package com.gatekeeper.nginx

import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerService
import com.gatekeeper.plugins.DatabaseFactory

/** Dedicated manual command entry point; invoke through the project's JVM launcher. */
fun main(args: Array<String>) {
    DatabaseFactory.init(AppSettings.fromEnv().db.url, AppSettings.fromEnv().db.user, AppSettings.fromEnv().db.password)
    try {
        val dryRun = args.contains("--dry-run")
        val path = args.firstOrNull { it != "--dry-run" } ?: AppSettings.fromEnv().nginx.sitesAvailablePath
        val docker = DockerService(AppSettings.fromEnv().docker.socket)
        try {
            NginxBackfillRunner.run(path, dryRun, docker)
        } finally {
            docker.close()
        }
    } finally {
        DatabaseFactory.close()
    }
}
