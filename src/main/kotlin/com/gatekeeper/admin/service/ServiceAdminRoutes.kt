package com.gatekeeper.admin.service


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get



fun Application.configureServiceAdminRoutes() {
    val dependencies = ServiceAdminDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            registerServiceManagementRoutes(dependencies)
            registerServiceBillingRoutes(dependencies)
            registerServiceRuntimeRoutes(dependencies)
        }
    }
}
