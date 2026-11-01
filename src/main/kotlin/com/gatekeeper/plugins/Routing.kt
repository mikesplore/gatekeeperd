package com.gatekeeper.plugins

import com.gatekeeper.admin.system.configureDockerAdminRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.get

internal suspend fun io.ktor.server.application.ApplicationCall.respondReadiness(
    response: HealthReadinessResponse
) {
    val status = if (response.status == "ready") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
    val json = Json.encodeToString(HealthReadinessResponse.serializer(), response)
    respondText(json, ContentType.Application.Json, status)
}

fun Application.configureRouting() {
    val health = get<HealthApplicationService>()
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        get("/api/version") { call.respond(health.version()) }
        get("/api/health/live") { call.respond(mapOf("status" to "ok")) }
        get("/health/live") { call.respond(mapOf("status" to "ok")) }

        get("/api/health/ready") {
            call.respondReadiness(health.readiness())
        }
        get("/health/ready") {
            call.respondReadiness(health.readiness())
        }
    }
    configureDockerAdminRoutes()
}
