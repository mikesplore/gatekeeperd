package com.gatekeeper.admin

import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository as CertificateRepositoryPort
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository as IntegrationOutboxRepositoryPort
import com.gatekeeper.feature.notification.domain.repository.NotificationRepository as NotificationRepositoryPort
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.DeleteManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectManualConfigUseCase
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import org.koin.ktor.ext.get
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.auth.authenticate



fun Application.configureOperationsAdminRoutes() {
    val dependencies = OperationsAdminDependencies(
        get(), get<AppSettings>().nginx, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()
    )
    routing {
        authenticate("auth-jwt") {
            registerTelemetryRoutes(dependencies)
            registerNginxOperationsRoutes(dependencies)
            registerCustomerDashboardRoutes(dependencies)
            registerNotificationAdminRoutes(dependencies)
            registerProjectOperationsRoutes(dependencies)
            registerAuditExportRoutes(dependencies)
        }
    }
}
