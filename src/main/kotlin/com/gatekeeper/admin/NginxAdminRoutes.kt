package com.gatekeeper.admin

import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.nginx.NginxService
import io.ktor.server.application.*
import org.koin.ktor.ext.get
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort




fun Application.configureNginxAdminRoutes() {
    val projects = get<ProjectRepository>()
    val nginxSettings = get<AppSettings>().nginx
    val nginxService = get<NginxService>()
    val auditRepository = get<AuditRepositoryPort>()
    val deploymentJobs = get<DeploymentJobRepository>()
    val serviceDomains = get<ServiceDomainRepository>()
    val dockerService: DockerGateway = get()
    val siteRepository = get<SiteRepository>()
    val serviceRepository = get<ServiceRepository>()
}