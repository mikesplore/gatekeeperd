package com.gatekeeper.admin

import com.gatekeeper.api.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.util.UUID

internal suspend fun ApplicationCall.setupProjectId(): UUID? {
    val id = parameters["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (id == null) respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is invalid")
    return id
}
