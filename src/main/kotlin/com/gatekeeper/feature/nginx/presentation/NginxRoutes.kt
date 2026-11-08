package com.gatekeeper.feature.nginx.presentation

import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Route.configureNginxRoutes() {
    nginxCertificateAdminRoutes(
        nginxAdministrationService = get(),
        certificateProvisioner = get()
    )
    nginxManualConfigRoutes(
        siteRepository = get(),
        nginxAdministrationService = get(),
        nginxSettings = get(),
        auditRepository = get(),
        preview = get(),
        inspector = get(),
        update = get(),
        projectRepository = get(),
        nginx = get(),
        serviceRepository = get(),
        listConfigArtifactsUseCase = get(),
        certificateProvisioner = get(),
        deploymentUpstreamResolver = get(),
        remove = get(),
        rollback = get(),
        renderModelFromSite = get()
    )
    nginxSiteAdminRoutes(
        applySiteConfig = get(),
        disableProjectUseCase = get(),
        projectRepository = get(),
        siteRepository = get(),
        serviceRepository = get(),
        validator = get(),
        serviceDomainRepository = get(),
        deploymentUpstreamResolver = get(),
        renderer = get(),
        certificateProvisioner = get(),
        nginx = get(),
        rollback = get(),
        conflictAnalyzer = get(),
        switchDeploymentUpstreamUseCase = get(),
        auditRepository = get(),
        removeSiteConfigUseCase = get(),
        renderModelFromSite = get(),
        planNginxEnableRequest = get(),
        nginxSettings = get(),
        convertSiteToDeploymentManagedUseCase = get()
    )
    nginxUpstreamRoutes(
        siteRepository = get(),
        dockerService = get(),
        deploymentJobs = get(),
        nginxSiteStorage = get(),
        repairDeploymentUpstreamUseCase = get(),
        convertSiteToDeploymentManagedUseCase = get()
    )
    nginxWizardAdminRoutes(
        siteRepository = get(),
        dockerService = get(),
        serviceRepository = get(),
        serviceDomains = get(),
        projectRepository = get(),
        deploymentUR = get(),
        certificationService = get(),
        validator = get(),
        nginxSettings = get(),
        renderer = get(),
        analyzer = get(),
        artifactsUseCase = get(),
        planNginxEnableRequest = get()
    )
}
