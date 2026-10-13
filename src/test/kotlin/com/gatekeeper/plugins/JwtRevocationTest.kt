package com.gatekeeper.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.SocketTimeoutException
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class JwtRevocationTest {
    private val secret = "unit-test-signing-secret-with-sufficient-length"
    private val audience = "gatekeeper-test-admin"
    private val issuer = "gatekeeper-test"

    @Test
    fun `valid non-revoked token is accepted while Redis is available`() {
        val response = protectedRequest(lookup = { null })
        assertEquals(HttpStatusCode.OK, response.first)
        assertEquals("ok", response.second)
    }

    @Test
    fun `revoked token is rejected while Redis is available`() {
        val response = protectedRequest(lookup = { "1" })
        assertEquals(HttpStatusCode.Unauthorized, response.first)
        assertFalse(response.second.contains("Bearer"))
    }

    @Test
    fun `Redis connection failure produces a controlled service error`() {
        val token = token()
        val response = protectedRequest(token) { throw IllegalStateException("redis.internal:6379 password detail") }
        assertServiceUnavailableWithoutDetails(response, token)
    }

    @Test
    fun `Redis timeout produces a controlled service error`() {
        val token = token()
        val response = protectedRequest(token) { throw SocketTimeoutException("redis.internal:6379 timed out") }
        assertServiceUnavailableWithoutDetails(response, token)
    }

    @Test
    fun `revoked token remains denied when Redis becomes unavailable`() {
        var redisAvailable = true
        val token = token("revoked-jti")
        val responses = protectedRequests(token, requestCount = 2) {
            if (!redisAvailable) throw SocketTimeoutException("redis.internal:6379 timed out")
            redisAvailable = false
            "1"
        }
        assertEquals(HttpStatusCode.Unauthorized, responses.first().first)
        assertServiceUnavailableWithoutDetails(responses.last(), token)
    }

    @Test
    fun `revocation checker distinguishes absent key revoked key invalid id and outage`() {
        assertEquals(JwtRevocationStatus.CONFIRMED_NOT_REVOKED, JwtConfig.revocationStatus("jti") { null })
        assertEquals(JwtRevocationStatus.REVOKED, JwtConfig.revocationStatus("jti") { "1" })
        assertEquals(JwtRevocationStatus.INVALID, JwtConfig.revocationStatus(null) { null })
        assertEquals(JwtRevocationStatus.UNAVAILABLE, JwtConfig.revocationStatus("jti") { error("redis failure") })
    }

    @Test
    fun `required Redis access rejects an uninitialized pool`() {
        assertFailsWith<RedisUnavailableException> { RedisService.getRequired("auth:revoked:test") }
        assertFailsWith<RedisUnavailableException> { RedisService.setRequired("auth:revoked:test", "1", 60) }
    }

    private fun protectedRequest(
        token: String = token(),
        lookup: (String) -> String?
    ): Pair<HttpStatusCode, String> = protectedRequests(token, lookup = lookup).single()

    private fun protectedRequests(
        token: String,
        requestCount: Int = 1,
        lookup: (String) -> String?
    ): List<Pair<HttpStatusCode, String>> {
        val responses = mutableListOf<Pair<HttpStatusCode, String>>()
        testApplication {
            application {
                configureSerialization()
                install(Authentication) {
                    configureAdminJwt(Algorithm.HMAC256(secret), audience, issuer, lookup)
                }
                routing {
                    authenticate("auth-jwt") {
                        get("/admin/test") { call.respondText("ok") }
                    }
                }
            }
            repeat(requestCount) {
                val response = client.get("/admin/test") { bearerAuth(token) }
                responses += response.status to response.bodyAsText()
            }
        }
        return responses
    }

    private fun assertServiceUnavailableWithoutDetails(
        response: Pair<HttpStatusCode, String>,
        token: String = "test-admin-token"
    ) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.first)
        assertEquals("revocation_check_unavailable", Json.parseToJsonElement(response.second).let { it as kotlinx.serialization.json.JsonObject }["error"]?.toString()?.trim('"'))
        assertFalse(response.second.contains(token))
        assertFalse(response.second.contains("redis.internal"))
        assertFalse(response.second.contains("password detail"))
    }

    private fun token(jti: String = "test-jti"): String = JWT.create()
        .withSubject("admin@example.test")
        .withIssuer(issuer)
        .withAudience(audience)
        .withJWTId(jti)
        .withClaim("role", "admin")
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
        .sign(Algorithm.HMAC256(secret))
}
