package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.util.UUID

internal suspend fun ApplicationCall.serviceProjectId(projects: ProjectRepository): UUID? {
    val raw = parameters["projectId"]
    if (raw == null) {
        respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is required")
        return null
    }
    val project = runCatching { UUID.fromString(raw) }.getOrNull()?.let(projects::findActiveById)
        ?: projects.findBySlug(raw)
    if (project == null) {
        respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
        return null
    }
    return project.id
}

internal suspend fun ApplicationCall.serviceCoordinates(projects: ProjectRepository): Pair<UUID, UUID>? {
    val projectId = serviceProjectId(projects) ?: return null
    val serviceId = parameters["serviceId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: run {
            respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
            return null
        }
    return projectId to serviceId
}
