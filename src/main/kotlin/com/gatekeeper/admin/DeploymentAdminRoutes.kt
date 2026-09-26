package com.gatekeeper.admin

import com.gatekeeper.deployment.DeploymentReconciliationService
import com.gatekeeper.config.AppConfig
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Application.configureDeploymentAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/deployments/reconciliation") {
                call.respond(DeploymentReconciliationService.production(AppConfig.dockerSocket).report())
            }
        }
    }
}
