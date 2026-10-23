package com.gatekeeper.admin

import com.gatekeeper.deployment.DeploymentReconciliationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.nginx.NginxService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureDeploymentAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/deployments/reconciliation") {
                call.respond(DeploymentReconciliationService.production(get<DockerGateway>(), get<NginxService>()).report())
            }
        }
    }
}
