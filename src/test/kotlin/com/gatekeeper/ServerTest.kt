package com.gatekeeper

import com.gatekeeper.gate.configureGateRoutes
import com.gatekeeper.plugins.configureSerialization
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

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
        }
        val response = client.get("/api/gate/pay?project=INVALID SLUG")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
