package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.admin.respondNginxDomainConflicts
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.ServiceDomainStatus
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerGateway
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
    dockerService: DockerGateway,
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
            val artifacts = artifactsUseCase.execute()
            val bindings = serviceDomains.listByService(serviceId).map { binding ->
                val target = binding.containerPort?.let {
                    deploymentUR.resolveRuntime(serviceId, it, "production")
                } ?: deploymentUR.resolveRuntime(serviceId, "production")
                NginxWizardDomainBindingResponse(
                    id = binding.id.toString(),
                    serviceId = binding.serviceId.toString(),
                    containerPort = binding.containerPort,
                    domain = binding.domain,
                    status = binding.status,
                    resolvedHostPort = target?.port,
                    nginxEnabled = siteRepository.findByDomain(binding.domain)?.let { bindingSite ->
                        artifacts.any { artifact ->
                            artifact.filename == bindingSite.projectSlug && artifact.available && artifact.enabled
                        }
                    } ?: false
                )
            }
            val activeRuntime = runCatching {
                DeploymentApplicationService.activeDeploymentRuntime(
                    serviceId,
                    "production"
                )
            }.getOrNull()
            val dockerHealth = runCatching {
                activeRuntime?.containerName?.let(dockerService::containerHealth)
            }.getOrNull()

            val installedCerts = certificationService.listInstalledCertificates().map { it.certificateDomain }.sorted()
            call.respond(
                NginxWizardContextResponse(
                    serviceId = serviceId.toString(),
                    domainBindings = bindings,
                    runtimeHealth = dockerHealth,
                    installedCertificates = installedCerts,
                )
            )
        }

        post("/api/admin/nginx/wizard/validate") {
            val body = try {
                call.receive<NginxEnableRequest>()
            } catch (_: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid request body")
                return@post
            }

            val serviceId = runCatching { java.util.UUID.fromString(body.serviceId) }.getOrNull()
            if (serviceId == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_service", "Selected service ID is invalid")
                return@post
            }
            val selectedServiceId = serviceId
            val service = serviceRepository.findById(selectedServiceId)
            if (service == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_service",
                    "Selected service was not found"
                )
                return@post
            }
            val bindingId = runCatching { java.util.UUID.fromString(body.domainBindingId) }.getOrNull()
            if (bindingId == null) {
                call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_domain_binding",
                    "Selected domain binding ID is invalid"
                )
                return@post
            }
            val binding = serviceDomains.findById(selectedServiceId, bindingId)
            if (binding == null || binding.status != ServiceDomainStatus.BOUND.value) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "domain_binding_unavailable",
                    "Select an available domain binding for this service"
                )
                return@post
            }
            val serviceDomain = body.domain.trim().trimEnd('.').lowercase()
            if (serviceDomain.isBlank() || serviceDomain != binding.domain.trim().trimEnd('.').lowercase()) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "domain_binding_mismatch",
                    "Selected domain does not match the selected service domain binding"
                )
                return@post
            }
            val project = projectRepository.findById(service.projectId)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project for this service was not found")
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
            val siteSlug = site?.projectSlug
                ?: nginxSiteSlug(project.slug, service.isDefault, serviceDomain, service.domain)

            when (val result = planNginxEnableRequest.planNginxEnableRequest(
                slug = siteSlug,
                projectDomain = serviceDomain,
                activeRuntimeTarget = binding.containerPort?.let {
                    deploymentUR.resolveRuntime(selectedServiceId, it, "production")
                } ?: deploymentUR.resolveRuntime(selectedServiceId, "production"),
                request = body
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
