package com.gatekeeper.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import java.util.*

object JwtConfig {
    val algorithm: Algorithm by lazy { Algorithm.HMAC256(AppConfig.jwtSecret) }

    fun createToken(email: String, role: String = "admin"): String {
        val now = Date()
        return JWT.create()
            .withSubject(email)
            .withIssuer(AppConfig.jwtIssuer)
            .withAudience(AppConfig.jwtAudience)
            .withClaim("role", role)
            .withIssuedAt(now)
            .withExpiresAt(Date(now.time + 24 * 3600 * 1000))
            .sign(algorithm)
    }
}

fun Application.configureSecurity() {
    install(DefaultHeaders) {
        header("X-Engine", "Gatekeeperd")
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHost("http://localhost:5173")
        allowHost("https://gatekeeperd.mikesplore.me")
        allowHost("http://gatekeeperd.mikesplore.me")
        allowCredentials = true
    }

    authentication {
        jwt("auth-jwt") {
            realm = "gatekeeperd"
            verifier(
                JWT
                    .require(JwtConfig.algorithm)
                    .withAudience(AppConfig.jwtAudience)
                    .withIssuer(AppConfig.jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(AppConfig.jwtAudience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
            }
        }
    }
}