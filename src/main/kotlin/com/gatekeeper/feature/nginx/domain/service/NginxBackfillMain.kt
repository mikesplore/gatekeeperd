package com.gatekeeper.feature.nginx.domain.service

import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGatewayFactory
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import com.gatekeeper.plugins.DatabaseFactory
import com.gatekeeper.security.SecretValueCipher
/** Dedicated manual command entry point; invoke through the project's JVM launcher. */
fun main(args: Array<String>) {
    val settings = AppSettings.fromEnv()
    SecretValueCipher.configure(settings.application)
    DatabaseFactory.init(settings.db.url, settings.db.user, settings.db.password, settings.db.migrationBaselineVersion)
    try {
        val dryRun = args.contains("--dry-run")
        val path = args.firstOrNull { it != "--dry-run" } ?: settings.nginx.sitesAvailablePath
        val docker = DockerGatewayFactory.create(settings.docker, ProcessRunnerImpl())
        try {
            NginxBackfillRunner.create(settings).run(path, dryRun, docker, settings)
        } finally {
            docker.close()
        }
    } finally {
        DatabaseFactory.close()
    }
}
