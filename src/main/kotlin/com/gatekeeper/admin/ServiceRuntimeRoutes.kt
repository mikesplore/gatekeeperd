package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentVersionMetadata
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.deployment.domain.repository.ActiveDeploymentInspection
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.service.domain.repository.ServiceRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.routing.*
import java.util.UUID
import java.math.BigDecimal
import org.koin.ktor.ext.get

fun Route.registerServiceRuntimeRoutes(dependencies: ServiceAdminDependencies) {
    val projects = dependencies.projects
    val auditRepository = dependencies.auditRepository
    val environmentSets = dependencies.environmentSets
    val scribed = dependencies.scribed
    val deploymentJobs = dependencies.deploymentJobs
    val serviceDomains = dependencies.serviceDomains
    val serviceRepository = dependencies.serviceRepository
    val siteRepository = dependencies.siteRepository
    val nginxSiteStorage = dependencies.nginxSiteStorage
    val docker = dependencies.docker
    val validator = dependencies.validator
    get("/api/admin/projects/{projectId}/environment/shared") {
        val projectId = call.serviceProjectId(projects) ?: return@get
        if (projects.findActiveById(projectId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment = if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        val metadata = environmentSets.sharedMetadata(projectId, environment)
        call.respond(SharedEnvironmentMetadataView(
            projectId.toString(), environment, metadata.latest?.toView(), metadata.versions.map { it.toView() }
        ))
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment = if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        val metadata = environmentSets.serviceMetadata(projectId, serviceId, environment)!!
        call.respond(ServiceEnvironmentMetadataView(
            projectId.toString(), serviceId.toString(), environment, metadata.effectiveValues
        ))
    }

    put("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@put
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val body = runCatching { call.receive<EnvironmentWriteRequest>() }.getOrNull()
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid service environment request")
        val environment = ServiceApplicationRules.normalizeEnvironment(body.environment)
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        if (!ServiceApplicationRules.validEnvironmentValues(body.values)) {
            return@put call.respondError(HttpStatusCode.BadRequest, "invalid_environment_values", "Environment variable names or values are invalid")
        }
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "admin"
        val result = runCatching {
            val sharedId = body.sharedEnvironmentSetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (body.sharedEnvironmentSetId != null && sharedId == null) {
                return@put call.respondError(HttpStatusCode.BadRequest, "invalid_shared_environment_reference", "Shared environment ID is invalid")
            }
            deploymentJobs.updateServiceEnvironmentAndDeploy(
                projectId, serviceId, environment, body.values, actor,
                replaceExisting = body.replaceExisting,
                sharedEnvironmentSetId = sharedId,
                sharedEnvironmentSetVersion = body.sharedEnvironmentSetVersion
            )
        }.getOrElse { error ->
            return@put call.respondError(HttpStatusCode.Conflict, "service_environment_update_failed", error.message ?: "Service environment could not be saved")
        } ?: return@put call.respondError(HttpStatusCode.Conflict, "source_runtime_required", "Save this service's source/runtime configuration before editing its environment")
        call.respond(HttpStatusCode.Accepted, EnvironmentWriteResponse(
            result.deploymentIds.map(UUID::toString)
        ))
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/active-deployment") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment = if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        val active = environmentSets.activeDeployment(serviceId, environment)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "active_deployment_not_found", "No active deployment exists for this service and environment")
        call.respond(active.toView())
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/runtime") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment = if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        val runtime = environmentSets.runtimeDeployment(serviceId, environment)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "runtime_not_found", "No active or ready deployment runtime exists for this service and environment")
        call.respond(runtime.toView())
    }
}
