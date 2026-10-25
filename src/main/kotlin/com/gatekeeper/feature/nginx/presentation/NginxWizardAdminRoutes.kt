package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.admin.respondNginxDomainConflicts
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.ServiceDomainStatus
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerService
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.nginx.domain.service.NginxConfigRendererService
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.CertbotCertificateProvisioner
import com.gatekeeper.feature.nginx.model.NginxPlanResult
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.nginxWizardAdminRoutes(
    siteRepository: SiteRepository,
    dockerService: DockerService,
    serviceRepository: ServiceRepository,
    serviceDomains: ServiceDomainRepository,
    projectRepository: ProjectRepository,
    deploymentUR: DeploymentUpstreamResolver,
    certificationService: CertbotCertificateProvisioner,
    validator: NginxValidationService,
    nginxSettings: AppSettings.NginxSettings,
    renderer: NginxConfigRendererService,
    analyzer: NginxConflictAnalyzer,
    artifactsUseCase: ListConfigArtifactsUseCase,
    planNginxEnableRequest: PlanNginxEnableRequest
){
    authenticate("auth-jwt") {
        get("/api/admin/nginx/wizard/context/{slug}") {
            val slug = call.parameters["slug"]
            if (slug == null) {
                call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                return@get
            }

            val project = projectRepository.findBySlug(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@get
            }

            val rawServiceId = call.request.queryParameters["serviceId"]
            val requestedServiceId = rawServiceId?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            if (rawServiceId != null && requestedServiceId == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_service", "Selected service ID is invalid")
                return@get
            }
            val service = requestedServiceId?.let { serviceRepository.findByProjectAndId(project.id, it) }
            if (requestedServiceId != null && service == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_service",
                    "Selected service does not belong to this project"
                )
                return@get
            }
            val serviceId = service?.id ?: siteRepository.findDefaultServiceId(project.id)
            val selectedService = serviceRepository.findByProjectAndId(project.id, serviceId)
                ?: run {
                    call.respondError(
                        HttpStatusCode.BadRequest,
                        "invalid_service",
                        "No valid service is available for this project"
                    )
                    return@get
                }
            val bindings = serviceDomains.listByService(serviceId).map {
                NginxWizardDomainBindingResponse(
                    it.id.toString(),
                    it.serviceId.toString(),
                    it.containerPort,
                    it.domain,
                    it.status
                )
            }
            val site = siteRepository.findByProjectIdAndServiceId(project.id, serviceId)
            val activeRuntime = runCatching {
                DeploymentApplicationService.activeDeploymentRuntime(
                    serviceId,
                    "production"
                )
            }.getOrNull()
            val resolvedTarget = deploymentUR.resolve(serviceId, "production")
            val configuredPort = resolvedTarget?.port

            val dockerHealth = runCatching {
                activeRuntime?.containerName?.let(dockerService::containerHealth)
            }.getOrNull()

            val installedCerts = certificationService.listInstalledCertificates().map { it.certificateDomain }.sorted()
            val domain = selectedService.domain
            val siteSlug = domain?.let {
                site?.projectSlug ?: nginxSiteSlug(
                    slug,
                    selectedService.isDefault,
                    it,
                    selectedService.domain
                )
            }
            val nginxEnabled = siteSlug?.let { name ->
                artifactsUseCase.execute().any { it.filename == name && it.available && it.enabled }
            } ?: false
            val resolvedCert = domain?.let(certificationService::resolveCertificateForDomain)

            call.respond(
                NginxWizardContextResponse(
                    slug = slug,
                    domain = domain?:"",
                    nginxEnabled = nginxEnabled,
                    resolvedUpstreamHost = resolvedTarget?.host,
                    configuredPort = configuredPort,
                    runtimeHealth = dockerHealth,
                    installedCertificates = installedCerts,
                    resolvedCertificateDomain = resolvedCert?.certificateDomain,
                )
            )
        }

        post("/api/admin/nginx/wizard/validate/{slug}") {
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

            val serviceId = body.serviceId?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            if (body.serviceId != null && serviceId == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_service", "Selected service ID is invalid")
                return@post
            }
            val selectedServiceId = serviceId ?: siteRepository.findDefaultServiceId(project.id)
            val service = serviceRepository.findByProjectAndId(project.id, selectedServiceId)
            if (service == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_service",
                    "Selected service does not belong to this project"
                )
                return@post
            }
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
            val serviceDomain = requestedDomain
            if (serviceDomain == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "service_domain_required",
                    "Configure a domain for this service before creating its Nginx site"
                )
                return@post
            }
            val binding = serviceDomains.findByServiceAndDomain(selectedServiceId, serviceDomain)
            if (binding == null || binding.status == ServiceDomainStatus.PORT_UNAVAILABLE.value) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "domain_binding_unavailable",
                    "Select an available domain binding for this service"
                )
                return@post
            }
            if (body.containerPort != null && body.containerPort != binding.containerPort) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "binding_port_mismatch",
                    "The selected domain is bound to a different container port"
                )
                return@post
            }
            val site = siteRepository.findByDomain(serviceDomain)
            if (site != null && site.serviceId != selectedServiceId) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "domain_in_use",
                    "Domain is already configured for another service"
                )
                return@post
            }
            val siteSlug =
                site?.projectSlug ?: nginxSiteSlug(slug, service.isDefault, serviceDomain, service.domain)

            when (val result = planNginxEnableRequest.planNginxEnableRequest(
                slug = siteSlug,
                projectDomain = serviceDomain,
                activeRuntimeTarget = binding.containerPort?.let {
                    deploymentUR.resolve(
                        selectedServiceId,
                        it,
                        "production"
                    )
                }
                    ?: deploymentUR.resolve(selectedServiceId, "production"),
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

                is NginxPlanResult.Ok -> {
                    val plan = result.plan
                    val config = renderer.generateNginxConfig(
                        slug = siteSlug,
                        domain = plan.domain,
                        appPort = plan.appPort,
                        upstreamScheme = plan.upstreamScheme,
                        sslEnabled = plan.sslEnabled,
                        sslCertificatePath = plan.resolvedCertificate?.certificatePath,
                        sslCertificateKeyPath = plan.resolvedCertificate?.privateKeyPath
                    )
                    val conflicts =
                        runCatching { analyzer.findDomainConflicts(config, siteSlug) }.getOrElse { error ->
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

                    call.respond(
                        NginxEnableResponse(
                            success = true,
                            message = "Validated successfully (no changes applied)",
                            config = config,
                            appPort = plan.appPort,
                            sslEnabled = plan.sslEnabled,
                            certificateDomain = plan.resolvedCertificate?.certificateDomain
                        )
                    )
                }
            }
        }

    }
}

fun nginxSiteSlug(
    projectSlug: String,
    isDefaultService: Boolean,
    domain: String,
    primaryDomain: String?
): String {
    if (isDefaultService && domain == primaryDomain) return projectSlug
    val suffix = domain.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "site" }
    return "$projectSlug-$suffix"
}