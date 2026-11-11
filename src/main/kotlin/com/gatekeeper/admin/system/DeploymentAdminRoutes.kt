package com.gatekeeper.admin.system


import com.gatekeeper.deployment.DeploymentReconciliationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureDeploymentAdminRoutes(
    dockerGateway: DockerGateway,
    projectRepository: ProjectRepository,
    siteRepository: SiteRepository,
    inspectSiteConfigUseCase: InspectSiteConfigUseCase,
    deploymentUpstreamResolver: DeploymentUpstreamResolver
) {
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            get("/api/admin/deployments/reconciliation") {
                val reconciliation = DeploymentReconciliationService.production(
                    projectRepository,
                    siteRepository,
                    dockerGateway,
                    deploymentUpstreamResolver,
                    inspectSiteConfigUseCase
                ).report()
                call.respond(reconciliation)
            }
        }
    }
}
