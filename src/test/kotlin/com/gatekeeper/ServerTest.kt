package com.gatekeeper

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

    @Test
    fun `health endpoint returns 200`() = testApplication {
        application { module() }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `admin projects requires authentication`() = testApplication {
        application { module() }
        val response = client.get("/api/admin/projects")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin containers requires authentication`() = testApplication {
        application { module() }
        val response = client.get("/api/admin/containers")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `gate pay rejects invalid slug`() = testApplication {
        application { module() }
        val response = client.get("/api/gate/pay?project=INVALID SLUG")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
