package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.config.AppSettings
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.deployment.*
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository as CertificateRepositoryPort
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository as IntegrationOutboxRepositoryPort
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.ConvertSiteToDeploymentManagedUseCase
import com.gatekeeper.feature.nginx.application.DeleteManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.notification.domain.repository.NotificationRepository as NotificationRepositoryPort
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get

data class OperationsAdminDependencies(
    val projects: ProjectRepository,
    val nginxSettings: AppSettings.NginxSettings,
    val notificationRepository: NotificationRepositoryPort,
    val certificateRepository: CertificateRepositoryPort,
    val auditRepository: AuditRepositoryPort,
    val integrationOutboxRepository: IntegrationOutboxRepositoryPort,
    val docker: DockerGateway,
    val portProbe: PortProbe,
    val siteRepository: SiteRepository,
    val deploymentUpstreamResolver: DeploymentUpstreamResolver,
    val certificateProvisioner: CertbotCertificateProvisioner,
    val inspectSiteConfig: InspectSiteConfigUseCase,
    val nginx: NginxSiteStorage,
    val nginxValidation: NginxValidationService,
    val render: NginxConfigRendererService,
    val conflictAnalyzer: NginxConflictAnalyzer,
    val applySiteConfig: ApplySiteConfigUseCase,
    val removesite: RemoveSiteConfigUseCase,
    val artifacts: ListConfigArtifactsUseCase,
    val inspector: InspectManualConfigUseCase,
    val delManualConfigUseCase: DeleteManualConfigUseCase
)

data class ProjectSetupDependencies(
    val projects: ProjectRepository,
    val auditRepository: AuditRepositoryPort,
    val docker: DockerGateway,
    val deploymentJobs: DeploymentJobRepository,
    val serviceDomains: ServiceDomainRepository,
    val portProbe: PortProbe,
    val serviceRepository: ServiceRepository,
    val siteRepository: SiteRepository,
    val projectRepository: ProjectRepository,
    val nginxValidation: NginxValidationService,
    val resolver: DeploymentUpstreamResolver,
    val inspector: InspectSiteConfigUseCase,
    val switchDeploymentUpstream: SwitchDeploymentUpstreamUseCase,
    val converter: ConvertSiteToDeploymentManagedUseCase,
    val nginxSiteStorage: NginxSiteStorage
)

data class ServiceAdminDependencies(
    val projects: ProjectRepository,
    val auditRepository: AuditRepositoryPort,
    val environmentSets: EnvironmentSetStore,
    val scribed: ScribedIntegrationClient,
    val deploymentJobs: DeploymentJobRepository,
    val serviceDomains: ServiceDomainRepository,
    val serviceRepository: ServiceRepository,
    val siteRepository: SiteRepository,
    val nginxSiteStorage: NginxSiteStorage,
    val docker: DockerGateway,
    val validator: NginxValidationService
)
