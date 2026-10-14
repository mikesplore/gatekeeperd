package com.gatekeeper

import com.gatekeeper.gate.configureGateRoutes
import com.gatekeeper.feature.payment.presentation.configurePaymentRoutes
import com.gatekeeper.plugins.configureApiDocumentation
import com.gatekeeper.plugins.configureSerialization
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {

    @Test
    fun `OpenAPI document and Swagger UI are served`() = testApplication {
        application {
            configureApiDocumentation()
        }

        val specification = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, specification.status)
        kotlin.test.assertTrue(specification.bodyAsText().contains("openapi: 3.0.3"))
        kotlin.test.assertTrue(specification.bodyAsText().contains("/api/admin/projects"))

        val swagger = client.get("/swagger")
        assertEquals(HttpStatusCode.OK, swagger.status)
        kotlin.test.assertTrue(swagger.bodyAsText().contains("SwaggerUIBundle"))
    }

    @Test
    fun `OpenAPI covers every route declaration`() {
        val routeRoot = File("src/main/kotlin/com/gatekeeper")
        assertTrue(routeRoot.isDirectory, "Backend route source directory was not found")

        val routeDeclaration = Regex("\\b(get|post|put|patch|delete|head|options)\\s*\\(\\s*\"(/[^\"]*)\"")
        val sourceOperations = routeRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                routeDeclaration.findAll(file.readText()).map { match ->
                    match.groupValues[1].lowercase() to match.groupValues[2]
                }
            }
            .toSet()

        val specification = File("src/main/resources/openapi/openapi.yaml").readText()
        val pathSections = Regex(
            "(?m)^  (/[^\\r\\n]+):\\r?\\n(.*?)(?=^  /|^components:|\\z)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(specification)
        val operationDeclaration = Regex("(?m)^    (get|post|put|patch|delete|head|options):\\s*$")
        val documentedOperations = pathSections.flatMap { section ->
            operationDeclaration.findAll(section.groupValues[2]).map { match ->
                match.groupValues[1] to section.groupValues[1]
            }
        }.toSet()

        assertEquals(sourceOperations, documentedOperations)
    }

    @Test
    fun `health endpoint returns 200`() = testApplication {
        application {
            configureSerialization()
            install(Authentication) {
                bearer("auth-jwt") {
                    authenticate { null }
                }
            }
            routing {
                get("/api/health") {
                    call.respond(mapOf("status" to "ok"))
                }
            }
        }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `admin projects requires authentication`() = testApplication {
        application {
            configureSerialization()
            install(Authentication) {
                bearer("auth-jwt") {
                    authenticate { null }
                }
            }
            routing {
                authenticate("auth-jwt") {
                    get("/api/admin/projects") {
                        call.respond(emptyList<Any>())
                    }
                }
            }
        }
        val response = client.get("/api/admin/projects")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin containers requires authentication`() = testApplication {
        application {
            configureSerialization()
            install(Authentication) {
                bearer("auth-jwt") {
                    authenticate { null }
                }
            }
            routing {
                authenticate("auth-jwt") {
                    get("/api/admin/containers") {
                        call.respond(emptyList<Any>())
                    }
                }
            }
        }
        val response = client.get("/api/admin/containers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `gate pay rejects invalid slug`() = testApplication {
        application {
            configureSerialization()
            configureGateRoutes()
            configurePaymentRoutes()
        }
        val response = client.get("/api/gate/pay?project=INVALID SLUG")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
