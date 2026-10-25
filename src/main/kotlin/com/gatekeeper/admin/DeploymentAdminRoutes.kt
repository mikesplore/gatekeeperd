package com.gatekeeper.admin

import com.gatekeeper.deployment.DeploymentReconciliationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.nginx.NginxService
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureDeploymentAdminRoutes(
    dockerGateway: DockerGateway,
    projectRepository: ProjectRepository,
    siteRepository: SiteRepository,
    inspectSiteConfigUseCase: InspectSiteConfigUseCase,
    deploymentUpstreamResolver: DeploymentUpstreamResolver
) {
    routing {
        authenticate("auth-jwt") {
            get("/api/admin/deployments/reconciliation") {
                call.respond(DeploymentReconciliationService.production(projectRepository,siteRepository, dockerGateway, deploymentUpstreamResolver, inspectSiteConfigUseCase))
            }
        }
    }
}
