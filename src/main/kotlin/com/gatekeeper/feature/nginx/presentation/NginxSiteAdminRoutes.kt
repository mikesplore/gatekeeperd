package com.gatekeeper.feature.nginx.presentation


import com.gatekeeper.admin.respondNginxDomainConflicts
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.ServiceDomainStatus
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ApplySiteConfigUseCase
import com.gatekeeper.feature.nginx.application.DisableProjectUseCase
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.RollbackSiteConfigUseCase
import com.gatekeeper.feature.nginx.application.SwitchDeploymentUpstreamUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRenderer
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteDashboardUpdate
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.RenderModelFromSite
import com.gatekeeper.feature.nginx.model.NginxPlanResult
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.collections.isNotEmpty
import kotlin.collections.orEmpty

fun Route.nginxSiteAdminRoutes(
    applySiteConfig: ApplySiteConfigUseCase,
    disableProjectUseCase: DisableProjectUseCase,
    projectRepository: ProjectRepository,
    siteRepository: SiteRepository,
    serviceRepository: ServiceRepository,
    validator: NginxValidationService,
    serviceDomainRepository: ServiceDomainRepository,
    deploymentUpstreamResolver: DeploymentUpstreamResolver,
    renderer: NginxConfigRenderer,
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
){

    authenticate("auth-jwt") {
        post("/api/admin/nginx/enable/{slug}") {
            val slug = call.parameters["slug"]
            if (slug == null) {
                call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                return@post
            }

            val project = projectRepository.findBySlug(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@post
            }

            val body = try {
                call.receive<NginxEnableRequest>()
            } catch (_: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                return@post
            }

            val parsedServiceId = body.serviceId?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            if (body.serviceId != null && parsedServiceId == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_service", "Selected service ID is invalid")
                return@post
            }
            val serviceId = parsedServiceId ?: siteRepository.findDefaultServiceId(project.id)
            val service = serviceRepository.findByProjectAndId(project.id, serviceId)
            if (service == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_service",
                    "Selected service does not belong to this project"
                )
                return@post
            }
            val activeRuntime = DeploymentApplicationService.activeRuntime(serviceId, "production")
            val requestedDomain = body.domain?.let { raw ->
                runCatching { validator.requireValidHostname(raw) }.getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_domain",
                        it.message ?: "Domain is invalid"
                    )
                    return@post
                }
            } ?: service.domain
            if (requestedDomain == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "service_domain_required",
                    "Configure a domain for this service before creating its Nginx site"
                )
                return@post
            }
            val binding = serviceDomainRepository.findByServiceAndDomain(serviceId, requestedDomain)
            if (binding == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "domain_binding_required",
                    "Add this domain to the service domain bindings before enabling nginx"
                )
                return@post
            }
            if (binding.status == ServiceDomainStatus.PORT_UNAVAILABLE.value) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "container_port_unavailable",
                    "This domain's container port is not published by the active deployment"
                )
                return@post
            }
            if (body.containerPort != null && binding.containerPort != body.containerPort) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "binding_port_mismatch",
                    "Update the service domain binding to change its container port"
                )
                return@post
            }
            val requestedContainerPort = binding.containerPort ?: activeRuntime?.ports?.keys?.singleOrNull()
            if ((activeRuntime?.ports?.size ?: 0) > 1 && requestedContainerPort == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "container_port_required",
                    "Select a containerPort when the active deployment publishes multiple ports"
                )
                return@post
            }
            if (requestedContainerPort != null && requestedContainerPort !in activeRuntime?.ports?.keys.orEmpty()) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "container_port_not_published",
                    "Selected container port is not published by the active deployment"
                )
                return@post
            }
            val site = siteRepository.findByDomain(requestedDomain)
            if (site != null && site.serviceId != serviceId) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "domain_in_use",
                    "Domain is already configured for another service"
                )
                return@post
            }
            val siteSlug =
                site?.projectSlug ?: nginxSiteSlug(slug, service.isDefault, requestedDomain, service.domain)
            val renderAffectingRequest = body.port != null || body.upstreamScheme != null ||
                    body.certificateDomain != null || body.sslCertificatePath != null ||
                    body.sslCertificateKeyPath != null || body.requireSsl != null
            if (site != null && renderAffectingRequest) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "site_parameters_not_allowed",
                    "This project is DB-driven; update its Site row instead of sending render-affecting enable parameters"
                )
                return@post
            }

            val responseAppPort: Int?
            val responseSslEnabled: Boolean
            val responseCertificateDomain: String?
            var siteToPersist: NginxSiteRenderModel? = null
            val siteForRender = if (site != null && requestedContainerPort != null) {
                site.copy(upstreamContainerPort = requestedContainerPort)
            } else site
            val config = if (siteForRender != null && siteForRender.domain == requestedDomain) {
                runCatching {
                    renderModelFromSite.renderModelFromSite(
                        siteForRender.projectSlug ?: siteSlug,
                        siteForRender
                    )
                }
                    .getOrElse {
                        call.respondError(
                            HttpStatusCode.UnprocessableEntity,
                            "site_configuration_invalid",
                            it.message ?: "Stored Site configuration is invalid"
                        )
                        return@post
                    }
                    .also {
                        responseAppPort = it.appPort
                        responseSslEnabled = it.tlsMode != TlsRenderMode.HTTP_ONLY
                        responseCertificateDomain =
                            if (siteForRender.certMode == CertMode.AUTO_RESOLVE) certificateProvisioner.resolveCertificateForDomain(
                                siteForRender.domain
                            )?.certificateDomain else "explicit"
                    }
                    .let { renderer.generateNginxConfig(it) }
            } else {
                val plan = when (val result = planNginxEnableRequest.planNginxEnableRequest(
                    slug = siteSlug,
                    projectDomain = requestedDomain,
                    activeRuntimeTarget = requestedContainerPort?.let {
                        deploymentUpstreamResolver.resolve(
                            serviceId,
                            it,
                            "production"
                        )
                    }
                        ?: deploymentUpstreamResolver.resolve(serviceId, "production"),
                    request = body,
                    sslCertPath = nginxSettings.sslCertPath
                )) {
                    is NginxPlanResult.Err -> {
                        if (result.data != null) {
                            call.respondErrorWithData(result.status, result.code, result.message, result.data)
                        } else {
                            call.respondError(result.status, result.code, result.message)
                        }
                        return@post
                    }

                    is NginxPlanResult.Ok -> result.plan
                }
                responseAppPort = plan.appPort
                responseSslEnabled = plan.sslEnabled
                responseCertificateDomain = plan.resolvedCertificate?.certificateDomain
                siteToPersist = NginxSiteRenderModel(
                    slug = siteSlug,
                    projectId = project.id,
                    serviceId = serviceId,
                    domain = plan.domain,
                    appPort = plan.appPort,
                    upstreamScheme = plan.upstreamScheme,
                    tlsMode = if (plan.sslEnabled) TlsRenderMode.HTTPS else TlsRenderMode.HTTP_ONLY,
                    certificatePath = plan.resolvedCertificate?.certificatePath.takeIf { body.sslCertificatePath != null },
                    certificateKeyPath = plan.resolvedCertificate?.privateKeyPath.takeIf { body.sslCertificatePath != null },
                    upstreamMode = UpstreamMode.EXPLICIT_PORT,
                    certMode = if (body.sslCertificatePath != null) CertMode.EXPLICIT_PATH else CertMode.AUTO_RESOLVE
                )
                renderer.generateNginxConfig(
                    slug = siteSlug,
                    domain = plan.domain,
                    appPort = responseAppPort,
                    upstreamScheme = plan.upstreamScheme,
                    sslEnabled = responseSslEnabled,
                    sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                    sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath,
                    projectId = project.id,
                    serviceId = serviceId
                )
            }

            val enabledSlug = siteToPersist?.slug ?: ""
            val conflicts =
                runCatching { conflictAnalyzer.findDomainConflicts(config, enabledSlug) }.getOrElse { error ->
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "nginx_config_scan_failed",
                        error.message ?: "Unable to inspect existing nginx configs"
                    )
                    return@post
                }
            if (conflicts.isNotEmpty()) {
                call.respondNginxDomainConflicts(conflicts)
                return@post
            }
            val enabled = applySiteConfig.enableProject(enabledSlug, config)
            if (!enabled) {
                val racedConflicts =
                    runCatching { conflictAnalyzer.findDomainConflicts(config, enabledSlug) }.getOrNull().orEmpty()
                if (racedConflicts.isNotEmpty()) {
                    call.respondNginxDomainConflicts(racedConflicts)
                    return@post
                }
                call.respondError(
                    HttpStatusCode.UnprocessableEntity,
                    "nginx_error",
                    "Nginx validation or activation failed; the previous site configuration was preserved"
                )
                return@post
            }

            val reloaded = nginx.reloadNginx()
            if (!reloaded) {
                disableProjectUseCase.disableProject(enabledSlug)
                call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to reload nginx")
                return@post
            }

            if (siteToPersist != null) {
                val model = siteToPersist
                val persisted = runCatching {
                    if (site == null) siteRepository.create(
                        project.id,
                        model.copy(slug = enabledSlug),
                        requestedContainerPort
                    )
                    else siteRepository.updateDashboard(
                        site.id, SiteDashboardUpdate(
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
                    .onFailure { error ->
                        nginxServiceLogger.error("Nginx site enabled but database registration failed for project $slug", error)
                        removeSiteConfigUseCase.execute(enabledSlug)
                        nginx.reloadNginx()
                        call.respondError(
                            HttpStatusCode.InternalServerError,
                            "site_registration_failed",
                            "Nginx site was enabled but could not be registered in the dashboard"
                        )
                        return@post
                    }
                if (persisted.getOrNull() == null) {
                    removeSiteConfigUseCase.execute(enabledSlug)
                    nginx.reloadNginx()
                    call.respondError(
                        HttpStatusCode.InternalServerError,
                        "site_registration_failed",
                        "Nginx site was enabled but could not be registered in the dashboard"
                    )
                    return@post
                }
            }

            nginxServiceLogger.info("Nginx enabled for project: $slug")
            call.respond(
                NginxEnableResponse(
                    success = true,
                    message = "Nginx site enabled and reloaded successfully",
                    config = config,
                    appPort = responseAppPort,
                    sslEnabled = responseSslEnabled,
                    certificateDomain = responseCertificateDomain
                )
            )
        }

        post("/api/admin/nginx/disable/{slug}") {
            val slug = call.parameters["slug"]
            if (slug == null) {
                call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                return@post
            }

            val disabled = disableProjectUseCase.disableProject(slug)
            if (!disabled) {
                call.respondError(HttpStatusCode.InternalServerError, "nginx_error", "Failed to disable nginx site")
                return@post
            }

            val reloaded = nginx.reloadNginx()
            if (!reloaded) {
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    "nginx_error",
                    "Failed to reload nginx after disable"
                )
                return@post
            }

            nginxServiceLogger.info("Nginx disabled for project: $slug")
            call.respond(
                NginxDisableResponse(
                    success = true,
                    message = "Nginx site disabled and reloaded successfully"
                )
            )
        }

        post("/api/admin/nginx/rollback/{slug}") {
            val slug = call.parameters["slug"]
            if (slug == null) {
                call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                return@post
            }
            val latest = runCatching { nginx.listBackups(slug).firstOrNull()?.name }
                .getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_slug",
                        it.message ?: "Invalid site name"
                    ); return@post
                }
            if (latest == null) {
                call.respondError(
                    HttpStatusCode.NotFound,
                    "nginx_backup_not_found",
                    "No nginx backup was found for this project"
                )
                return@post
            }
            val result = runCatching { rollback.execute(slug, latest) }
                .getOrElse {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "rollback_failed",
                        it.message ?: "Unable to roll back configuration"
                    ); return@post
                }
            if (!result.success) call.respond(HttpStatusCode.UnprocessableEntity, result) else call.respond(result)
            if (result.success) projectRepository.findBySlug(slug)?.let { project ->
                auditRepository.write(
                    project.id,
                    "nginx_rollback",
                    "admin",
                    "Rolled back Nginx configuration to $latest"
                )
            }
        }

        post("/api/admin/nginx/upstream-migration/sites/{siteId}/rollback") {
            val siteId =
                call.parameters["siteId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_site_id",
                        "A valid site ID is required"
                    )
            val site = siteRepository.findById(siteId)
                ?: return@post call.respondError(HttpStatusCode.NotFound, "site_not_found", "Site not found")
            if (site.upstreamKind != "DEPLOYMENT_MANAGED") {
                return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "site_not_deployment_managed",
                    "Site is not converted to deployment-managed upstream mode"
                )
            }
            val request = runCatching { call.receive<UpstreamMigrationRollbackRequest>() }.getOrNull()
                ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "A site backup name is required"
                )
            val slug = site.projectSlug
                ?: return@post call.respondError(
                    HttpStatusCode.Conflict,
                    "site_slug_missing",
                    "Site has no nginx configuration name"
                )
            val result = runCatching { rollback.execute(slug, request.backupName) }.getOrElse { error ->
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "upstream_migration_rollback_failed",
                    error.message ?: "Could not restore the saved site config"
                )
            }
            if (!result.success) {
                return@post call.respondError(
                    HttpStatusCode.BadGateway,
                    "upstream_migration_rollback_failed",
                    result.message
                )
            }
            siteRepository.restoreUpstreamKind(
                site.id,
                if (site.upstreamMode == UpstreamMode.EXPLICIT_PORT) "EXPLICIT_PORT" else null
            )
            val includeRemoved = site.serviceId?.let { switchDeploymentUpstreamUseCase.restoreDeploymentUpstream(it, null) } ?: true
            call.respond(
                mapOf(
                    "success" to true,
                    "status" to if (includeRemoved) "rolled_back" else "rolled_back_include_cleanup_pending",
                    "serviceId" to site.serviceId?.toString()
                )
            )
        }

    }
}