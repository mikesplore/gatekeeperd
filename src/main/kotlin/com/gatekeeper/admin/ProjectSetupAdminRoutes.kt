package com.gatekeeper.admin

import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.deployment.*
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ConvertSiteToDeploymentManagedUseCase
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get



fun Application.configureProjectSetupAdminRoutes() {
    val dependencies = ProjectSetupDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    routing {
        authenticate("auth-jwt") {
            registerProjectSetupRoutes(dependencies)
            registerProjectDeploymentRoutes(dependencies)
        }
    }
}
