package com.gatekeeper.admin.service


import com.gatekeeper.admin.application.ServiceApplicationRules
import com.gatekeeper.admin.application.serviceCoordinates
import com.gatekeeper.admin.application.serviceProjectId
import com.gatekeeper.admin.application.toView
import com.gatekeeper.admin.model.*
import com.gatekeeper.api.respondError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Route.registerServiceRuntimeRoutes(dependencies: ServiceAdminDependencies) {
    val projects = dependencies.projects
    val environmentSets = dependencies.environmentSets
    val deploymentJobs = dependencies.deploymentJobs
    val serviceRepository = dependencies.serviceRepository

    get("/api/admin/projects/{projectId}/environment/shared") {
        val projectId = call.serviceProjectId(projects) ?: return@get
        if (projects.findActiveById(projectId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment =
            if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
                ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_environment",
                    "Environment name is invalid"
                )
        val metadata = environmentSets.sharedMetadata(projectId, environment)
        call.respond(
            SharedEnvironmentMetadataView(
            projectId.toString(), environment, metadata.latest?.toView(), metadata.versions.map { it.toView() }
        ))
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment =
            if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
                ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_environment",
                    "Environment name is invalid"
                )
        val metadata = environmentSets.serviceMetadata(projectId, serviceId, environment)!!
        call.respond(
            ServiceEnvironmentMetadataView(
                projectId.toString(), serviceId.toString(), environment, metadata.effectiveValues
            )
        )
    }

    put("/api/admin/projects/{projectId}/services/{serviceId}/environment") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@put
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@put call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val body = runCatching { call.receive<EnvironmentWriteRequest>() }.getOrNull()
            ?: return@put call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid service environment request"
            )
        val environment = ServiceApplicationRules.normalizeEnvironment(body.environment)
            ?: return@put call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_environment",
                "Environment name is invalid"
            )
        if (!ServiceApplicationRules.validEnvironmentValues(body.values)) {
            return@put call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_environment_values",
                "Environment variable names or values are invalid"
            )
        }
        val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "admin"
        val result = runCatching {
            val sharedId = body.sharedEnvironmentSetId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (body.sharedEnvironmentSetId != null && sharedId == null) {
                return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_shared_environment_reference",
                    "Shared environment ID is invalid"
                )
            }
            deploymentJobs.updateServiceEnvironmentAndDeploy(
                projectId, serviceId, environment, body.values, actor,
                replaceExisting = body.replaceExisting,
                sharedEnvironmentSetId = sharedId,
                sharedEnvironmentSetVersion = body.sharedEnvironmentSetVersion
            )
        }.getOrElse { error ->
            return@put call.respondError(
                HttpStatusCode.Conflict,
                "service_environment_update_failed",
                error.message ?: "Service environment could not be saved"
            )
        } ?: return@put call.respondError(
            HttpStatusCode.Conflict,
            "source_runtime_required",
            "Save this service's source/runtime configuration before editing its environment"
        )
        call.respond(
            HttpStatusCode.Accepted, EnvironmentWriteResponse(
                result.deploymentIds.map(UUID::toString)
            )
        )
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/active-deployment") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment =
            if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
                ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_environment",
                    "Environment name is invalid"
                )
        val active = environmentSets.activeDeployment(serviceId, environment)
            ?: return@get call.respondError(
                HttpStatusCode.NotFound,
                "active_deployment_not_found",
                "No active deployment exists for this service and environment"
            )
        call.respond(active.toView())
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/runtime") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        if (serviceRepository.findByProjectAndId(projectId, serviceId) == null) {
            return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment =
            if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
                ?: return@get call.respondError(
                    HttpStatusCode.BadRequest,
                    "invalid_environment",
                    "Environment name is invalid"
                )
        val runtime = environmentSets.runtimeDeployment(serviceId, environment)
            ?: return@get call.respondError(
                HttpStatusCode.NotFound,
                "runtime_not_found",
                "No active or ready deployment runtime exists for this service and environment"
            )
        call.respond(runtime.toView())
    }

    get("/api/admin/projects/{projectId}/services/{serviceId}/connection-details") {
        val (projectId, serviceId) = call.serviceCoordinates(projects) ?: return@get
        val service = serviceRepository.findByProjectAndId(projectId, serviceId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
        if (service.serviceType !in setOf("database", "redis")) {
            return@get call.respondError(HttpStatusCode.Conflict, "not_a_data_service", "Connection details are available for database and Redis services")
        }
        val rawEnvironment = call.request.queryParameters["environment"]
        val environment = if (rawEnvironment == null) "production" else ServiceApplicationRules.normalizeEnvironment(rawEnvironment)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_environment", "Environment name is invalid")
        val runtime = environmentSets.runtimeDeployment(serviceId, environment)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "runtime_not_found", "No active or ready database deployment exists for this environment")
        val containerName = runtime.containerName
            ?: return@get call.respondError(HttpStatusCode.Conflict, "container_unavailable", "The database deployment has no inspectable container")
        val details = runCatching { dependencies.docker.getContainerPresetCredentials(containerName) }.getOrElse {
            return@get call.respondError(HttpStatusCode.ServiceUnavailable, "connection_details_unavailable", "Docker could not inspect this database container")
        } ?: return@get call.respondError(
            HttpStatusCode.Conflict,
            "database_credentials_not_detected",
            "No supported PostgreSQL or Redis connection variables were detected in this container"
        )
        call.respond(details)
    }
}
