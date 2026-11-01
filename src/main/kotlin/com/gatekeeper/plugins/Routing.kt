package com.gatekeeper.plugins

import com.gatekeeper.admin.system.configureDockerAdminRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get

fun Application.configureRouting() {
    val health = get<HealthApplicationService>()
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        get("/api/version") { call.respond(health.version()) }
        get("/api/health/live") { call.respond(mapOf("status" to "ok")) }
        get("/health/live") { call.respond(mapOf("status" to "ok")) }

        get("/api/health/ready") {
            val status = health.readiness()
            call.respond(if (status.status == "ready") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, status)
        }
        get("/health/ready") {
            val status = health.readiness()
            call.respond(if (status.status == "ready") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, status)
        }
    }
    configureDockerAdminRoutes()
}
