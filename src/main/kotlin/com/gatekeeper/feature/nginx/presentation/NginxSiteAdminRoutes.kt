package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.DisableProjectUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.RollbackSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.RenderModelFromSite
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.nginxSiteAdminRoutes(
    applySiteConfig: ApplySiteConfigUseCase,
    disableProjectUseCase: DisableProjectUseCase,
    projectRepository: ProjectRepository,
    siteRepository: SiteRepository,
    serviceRepository: ServiceRepository,
    validator: NginxValidationService,
    serviceDomainRepository: ServiceDomainRepository,
    deploymentUpstreamResolver: DeploymentUpstreamResolver,
    renderer: NginxConfigRendererService,
    certificateProvisioner: CertbotCertificateProvisioner,
    nginx: NginxSiteStorage,
    rollback: RollbackSiteConfigUseCase,
    conflictAnalyzer: NginxConflictAnalyzer,
    switchDeploymentUpstreamUseCase: SwitchDeploymentUpstreamUseCase,
    auditRepository: AuditRepository,
    removeSiteConfigUseCase: RemoveSiteConfigUseCase,
    renderModelFromSite: RenderModelFromSite,
    planNginxEnableRequest: PlanNginxEnableRequest,
    nginxSettings: AppSettings.NginxSettings
) {
    val enableHandler = NginxSiteEnableHandler(
        applySiteConfig = applySiteConfig,
        disableProjectUseCase = disableProjectUseCase,
        projectRepository = projectRepository,
        siteRepository = siteRepository,
        serviceRepository = serviceRepository,
        serviceDomainRepository = serviceDomainRepository,
        deploymentUpstreamResolver = deploymentUpstreamResolver,
        renderer = renderer,
        certificateProvisioner = certificateProvisioner,
        nginx = nginx,
        conflictAnalyzer = conflictAnalyzer,
        removeSiteConfigUseCase = removeSiteConfigUseCase,
        renderModelFromSite = renderModelFromSite,
        planNginxEnableRequest = planNginxEnableRequest
    )

    val handler = NginxSiteAdminHandler(
        disableProjectUseCase = disableProjectUseCase,
        projectRepository = projectRepository,
        siteRepository = siteRepository,
        nginx = nginx,
        rollback = rollback,
        switchDeploymentUpstreamUseCase = switchDeploymentUpstreamUseCase,
        auditRepository = auditRepository
    )

    authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
        post("/api/admin/nginx/enable") {
            enableHandler.enable(call)
        }

        post("/api/admin/nginx/disable/{slug}") {
            handler.disable(call)
        }

        post("/api/admin/nginx/rollback/{slug}") {
            handler.rollbackLatest(call)
        }

        post("/api/admin/nginx/upstream-migration/sites/{siteId}/rollback") {
            handler.rollbackUpstreamMigration(call)
        }
    }
}
