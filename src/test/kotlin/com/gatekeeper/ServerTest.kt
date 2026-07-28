package com.gatekeeper

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `test health endpoint returns 200`() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
