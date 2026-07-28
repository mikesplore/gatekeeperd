package com.gatekeeper

import com.gatekeeper.plugins.configureMonitoring
import com.gatekeeper.plugins.configureRouting
import com.gatekeeper.plugins.configureSecurity
import com.gatekeeper.plugins.configureSerialization
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `test health endpoint returns 200`() = testApplication {
        application {
            configureSerialization()
            configureMonitoring()
            configureSecurity()
            configureRouting()
        }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}