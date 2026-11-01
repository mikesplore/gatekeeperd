package com.gatekeeper.plugins

import com.gatekeeper.deployment.DeploymentVersionService

class HealthApplicationService {
    fun version() = DeploymentVersionService().current()

    fun readiness(): HealthReadinessResponse {
        val database = DatabaseFactory.isHealthy()
        val redis = RedisService.isHealthy()
        val migrations = DatabaseFactory.migrationsHealthy()
        val ready = database && redis && migrations
        return HealthReadinessResponse(
            status = if (ready) "ready" else "not_ready",
            dependencies = HealthDependencyStatus(database, redis, migrations)
        )
    }
}
