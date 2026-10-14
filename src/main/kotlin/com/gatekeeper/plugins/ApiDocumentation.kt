package com.gatekeeper.plugins

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.plugins.swagger.swaggerUI

private const val OPENAPI_RESOURCE = "openapi/openapi.yaml"

fun Application.configureApiDocumentation() {
    routing {
        get("/openapi.yaml") {
            val specification = requireNotNull(
                this::class.java.classLoader.getResourceAsStream(OPENAPI_RESOURCE)
            ) { "OpenAPI specification resource '$OPENAPI_RESOURCE' is missing" }
                .bufferedReader()
                .use { it.readText() }
            call.respondText(specification, ContentType.parse("application/yaml"))
        }

        swaggerUI(path = "/swagger", swaggerFile = OPENAPI_RESOURCE)
    }
}
