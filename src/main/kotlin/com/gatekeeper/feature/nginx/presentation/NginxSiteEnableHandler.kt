package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.admin.respondNginxDomainConflicts
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ServiceDomainStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.DisableProjectUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.RenderModelFromSite
import com.gatekeeper.feature.nginx.model.NginxPlanResult
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteDashboardUpdate
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.UUID
import java.util.UUID.fromString

class NginxSiteEnableHandler(
    private val applySiteConfig: ApplySiteConfigUseCase,
    private val disableProjectUseCase: DisableProjectUseCase,
    private val projectRepository: ProjectRepository,
    private val siteRepository: SiteRepository,
    private val serviceRepository: ServiceRepository,
    private val serviceDomainRepository: ServiceDomainRepository,
    private val deploymentUpstreamResolver: DeploymentUpstreamResolver,
    private val renderer: NginxConfigRendererService,
    private val certificateProvisioner: CertbotCertificateProvisioner,
    private val nginx: NginxSiteStorage,
    private val conflictAnalyzer: NginxConflictAnalyzer,
    private val removeSiteConfigUseCase: RemoveSiteConfigUseCase,
    private val renderModelFromSite: RenderModelFromSite,
    private val planNginxEnableRequest: PlanNginxEnableRequest
) {
    suspend fun enable(call: ApplicationCall) {
        val body = call.receiveEnableRequestOrRespond() ?: return
        val serviceId = runCatching { fromString(body.serviceId) }.getOrNull()
        if (serviceId == null) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_service", "Selected service ID is invalid")
            return
        }

        val service = serviceRepository.findById(serviceId)
        if (service == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_service",
                "Selected service does not belong to this project"
            )
            return
        }

        val bindingId = runCatching { fromString(body.domainBindingId) }.getOrNull()
        if (bindingId == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_domain_binding",
                "Selected domain binding ID is invalid"
            )
            return
        }
        nginxServiceLogger.info("Selected domain binding ID: $bindingId")
        val binding = serviceDomainRepository.findById(serviceId, bindingId)
        nginxServiceLogger.info("Selected domain binding: $binding")
        if (binding == null || binding.status != ServiceDomainStatus.BOUND.value) {
            call.respondError(
                HttpStatusCode.Conflict,
                "domain_binding_unavailable",
                "Select an available domain binding for this service"
            )
            return
        }

        val requestedDomain = body.domain.trim().trimEnd('.').lowercase()
        val boundDomain = binding.domain.trim().trimEnd('.').lowercase()
        if (requestedDomain.isBlank() || requestedDomain != boundDomain) {
            call.respondError(
                HttpStatusCode.Conflict,
                "domain_binding_mismatch",
                "Selected domain does not match the selected service domain binding"
            )
            return
        }
        val requestedContainerPort = binding.containerPort
        val site = siteRepository.findByDomain(requestedDomain)
        nginxServiceLogger.info(
            "Selected domain: $requestedDomain; existing site service ID: ${site?.serviceId?.toString() ?: "none"}; selected service ID: $serviceId"
        )

        if (site != null && site.serviceId != serviceId) {
            call.respondError(
                HttpStatusCode.Conflict,
                "domain_in_use",
                "Domain is already configured for another service"
            )
            return
        }

        val project = projectRepository.findById(service.projectId)
        if (project == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_project",
                "Project for this service was not found"
            )
            return
        }

        val siteSlug = site?.projectSlug ?: project.slug

        val rendered = renderEnableConfig(
            call = call,
            body = body,
            site = site,
            siteSlug = siteSlug,
            requestedDomain = requestedDomain,
            requestedContainerPort = requestedContainerPort,
            serviceProjectId = service.projectId,
            parsedServiceId = serviceId
        ) ?: return

        val enabledSlug = rendered.siteToPersist?.slug ?: siteSlug

        if (!checkConflicts(call, rendered.config, enabledSlug)) return
        if (!enableAndReload(call, enabledSlug, rendered.config)) return

        if (rendered.siteToPersist != null) {
            val persisted = persistSiteOrRollback(
                call = call,
                site = site,
                model = rendered.siteToPersist,
                enabledSlug = enabledSlug,
                requestedContainerPort = requestedContainerPort,
                projectSlug = enabledSlug
            )

            if (!persisted) return
        }

        nginxServiceLogger.info("Nginx enabled for project: $enabledSlug")
        call.respond(
            NginxEnableResponse(
                success = true,
                message = "Nginx site enabled and reloaded successfully",
                config = rendered.config,
                appPort = rendered.appPort,
                sslEnabled = rendered.sslEnabled,
                certificateDomain = rendered.certificateDomain
            )
        )
    }

    private suspend fun renderEnableConfig(
        call: ApplicationCall,
        body: NginxEnableRequest,
        site: SiteRecord?,
        siteSlug: String,
        requestedDomain: String,
        requestedContainerPort: Int?,
        serviceProjectId: UUID,
        parsedServiceId: UUID
    ): RenderedEnableConfig? {
        var siteToPersist: NginxSiteRenderModel

        val siteForRender = if (site != null && requestedContainerPort != null) {
            site.copy(upstreamContainerPort = requestedContainerPort)
        } else {
            site
        }

        if (siteForRender != null && siteForRender.domain == requestedDomain) {
            val model = runCatching {
                renderModelFromSite.renderModelFromSite(
                    siteForRender.projectSlug ?: siteSlug,
                    siteForRender
                )
            }.getOrElse {
                call.respondError(
                    HttpStatusCode.UnprocessableEntity,
                    "site_configuration_invalid",
                    it.message ?: "Stored Site configuration is invalid"
                )
                return null
            }

            return RenderedEnableConfig(
                config = renderer.generateNginxConfig(model),
                appPort = model.appPort,
                sslEnabled = model.tlsMode != TlsRenderMode.HTTP_ONLY,
                certificateDomain = if (siteForRender.certMode == CertMode.AUTO_RESOLVE) {
                    certificateProvisioner.resolveCertificateForDomain(siteForRender.domain)?.certificateDomain
                } else {
                    "explicit"
                },
                siteToPersist = null
            )
        }

        val plan = when (val result = planNginxEnableRequest.planNginxEnableRequest(
            slug = siteSlug,
            projectDomain = requestedDomain,
            activeRuntimeTarget = requestedContainerPort?.let {
                deploymentUpstreamResolver.resolveRuntime(parsedServiceId, it, "production")
            } ?: deploymentUpstreamResolver.resolveRuntime(parsedServiceId, "production"),
            request = body
        )) {
            is NginxPlanResult.Err -> {
                if (result.data != null) {
                    call.respondErrorWithData(result.status, result.code, result.message, result.data)
                } else {
                    call.respondError(result.status, result.code, result.message)
                }
                return null
            }

            is NginxPlanResult.Ok -> result.plan
        }

        siteToPersist = NginxSiteRenderModel(
            slug = siteSlug,
            projectId = serviceProjectId,
            serviceId = parsedServiceId,
            domain = plan.domain,
            appPort = plan.appPort,
            upstreamScheme = plan.upstreamScheme,
            tlsMode = if (plan.sslEnabled) TlsRenderMode.HTTPS else TlsRenderMode.HTTP_ONLY,
            certificatePath = null,
            certificateKeyPath = null,
            upstreamMode = UpstreamMode.DOCKER_DISCOVERY,
            certMode = CertMode.AUTO_RESOLVE
        )

        return RenderedEnableConfig(
            config = renderer.generateNginxConfig(
                slug = siteSlug,
                domain = plan.domain,
                appPort = plan.appPort,
                upstreamScheme = plan.upstreamScheme,
                sslEnabled = plan.sslEnabled,
                sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath,
                projectId = serviceProjectId,
                serviceId = parsedServiceId
            ),
            appPort = plan.appPort,
            sslEnabled = plan.sslEnabled,
            certificateDomain = plan.resolvedCertificate?.certificateDomain,
            siteToPersist = siteToPersist
        )
    }

    private suspend fun checkConflicts(
        call: ApplicationCall,
        config: String,
        enabledSlug: String
    ): Boolean {
        val conflicts = runCatching {
            conflictAnalyzer.findDomainConflicts(config, enabledSlug)
        }.getOrElse { error ->
            call.respondError(
                HttpStatusCode.InternalServerError,
                "nginx_config_scan_failed",
                error.message ?: "Unable to inspect existing nginx configs"
            )
            return false
        }

        if (conflicts.isNotEmpty()) {
            call.respondNginxDomainConflicts(conflicts)
            return false
        }

        return true
    }

    private suspend fun enableAndReload(
        call: ApplicationCall,
        enabledSlug: String,
        config: String
    ): Boolean {
        val enabled = applySiteConfig.enableProject(enabledSlug, config)
        if (!enabled) {
            val racedConflicts = runCatching {
                conflictAnalyzer.findDomainConflicts(config, enabledSlug)
            }.getOrNull().orEmpty()

            if (racedConflicts.isNotEmpty()) {
                call.respondNginxDomainConflicts(racedConflicts)
                return false
            }

            nginxServiceLogger.error("Nginx site enable failed for project $enabledSlug")
            call.respondError(
                HttpStatusCode.UnprocessableEntity,
                "nginx_error",
                "Nginx validation or activation failed; the previous site configuration was preserved"
            )
            return false
        }

        val reloaded = nginx.reloadNginx()
        if (!reloaded) {
            disableProjectUseCase.disableProject(enabledSlug)
            call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx")
            return false
        }

        return true
    }

    private suspend fun persistSiteOrRollback(
        call: ApplicationCall,
        site: SiteRecord?,
        model: NginxSiteRenderModel,
        enabledSlug: String,
        requestedContainerPort: Int?,
        projectSlug: String
    ): Boolean {
        val persisted = runCatching {
            if (site == null) {
                siteRepository.create(
                    projectId = model.projectId?: return@runCatching false ,
                    model.copy(slug = enabledSlug),
                    requestedContainerPort
                )
            } else {
                siteRepository.updateDashboard(
                    site.id,
                    SiteDashboardUpdate(
                        domain = model.domain,
                        upstreamHost = model.upstreamHost,
                        upstreamMode = model.upstreamMode,
                        upstreamExplicitPort = model.appPort,
                        upstreamContainerPort = requestedContainerPort ?: site.upstreamContainerPort,
                        tlsMode = when (model.tlsMode) {
                            TlsRenderMode.HTTP_ONLY -> TlsMode.HTTP_ONLY
                            TlsRenderMode.HTTPS -> TlsMode.HTTPS
                            TlsRenderMode.HTTPS_HTTP2 -> TlsMode.HTTPS_HTTP2
                        },
                        certMode = model.certMode,
                        certExplicitPath = model.certificatePath,
                        gateEnabled = true
                    )
                )
            }
        }.onFailure { error ->
            nginxServiceLogger.error(
                "Nginx site enabled but database registration failed for project $projectSlug",
                error
            )
            rollbackEnabledSite(enabledSlug)
            call.respondError(
                HttpStatusCode.InternalServerError,
                "site_registration_failed",
                "Nginx site was enabled but could not be registered in the dashboard"
            )
            return false
        }

        if (persisted.getOrNull() == null) {
            rollbackEnabledSite(enabledSlug)
            call.respondError(
                HttpStatusCode.InternalServerError,
                "site_registration_failed",
                "Nginx site was enabled but could not be registered in the dashboard"
            )
            return false
        }

        return true
    }

    private fun rollbackEnabledSite(enabledSlug: String) {
        removeSiteConfigUseCase.execute(enabledSlug)
        nginx.reloadNginx()
    }
}

private data class RenderedEnableConfig(
    val config: String,
    val appPort: Int?,
    val sslEnabled: Boolean,
    val certificateDomain: String?,
    val siteToPersist: NginxSiteRenderModel?
)

private suspend fun ApplicationCall.receiveEnableRequestOrRespond(): NginxEnableRequest? =
    try {
        nginxServiceLogger.info("Received nginx enable request")
        receive<NginxEnableRequest>()
    } catch (_: Exception) {
        respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
        null
    }
