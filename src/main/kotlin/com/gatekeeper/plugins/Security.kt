package com.gatekeeper.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import org.slf4j.LoggerFactory
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.util.AttributeKey
import org.koin.ktor.ext.get
import java.util.*

internal enum class JwtRevocationStatus {
    CONFIRMED_NOT_REVOKED,
    REVOKED,
    INVALID,
    UNAVAILABLE
}

private val revocationUnavailableKey = AttributeKey<Boolean>("jwt-revocation-unavailable")

object JwtConfig {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.JwtConfig")
    private val supportedRoles = setOf("admin", "operator", "viewer")
    fun algorithm(settings: AppSettings.AuthSettings): Algorithm = Algorithm.HMAC256(settings.jwtSecret)

    fun createToken(settings: AppSettings.AuthSettings, email: String, role: String = "admin"): String {
        require(role in supportedRoles) { "Unsupported JWT role" }
        val now = Date()
        return JWT.create()
            .withSubject(email)
            .withIssuer(settings.issuer)
            .withAudience(settings.audience)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("role", role)
            .withIssuedAt(now)
            .withExpiresAt(Date(now.time + 24 * 3600 * 1000))
            .sign(algorithm(settings))
    }

    internal fun revocationStatus(
        jti: String?,
        lookup: (String) -> String? = RedisService::getRequired
    ): JwtRevocationStatus {
        if (jti.isNullOrBlank()) return JwtRevocationStatus.INVALID
        return try {
            if (lookup("auth:revoked:$jti") != null) JwtRevocationStatus.REVOKED
            else JwtRevocationStatus.CONFIRMED_NOT_REVOKED
        } catch (_: Exception) {
            // Do not log exception text: client libraries may include connection or credential details.
            logger.warn("Token revocation check unavailable")
            JwtRevocationStatus.UNAVAILABLE
        }
    }
}

internal fun io.ktor.server.auth.AuthenticationConfig.configureAdminJwt(
    algorithm: Algorithm,
    audience: String,
    issuer: String,
    revocationLookup: (String) -> String?
) {
    configureRoleJwt("auth-jwt", setOf("admin", "operator", "viewer"), algorithm, audience, issuer, revocationLookup)
    configureRoleJwt("auth-admin", setOf("admin"), algorithm, audience, issuer, revocationLookup)
}

private fun io.ktor.server.auth.AuthenticationConfig.configureRoleJwt(
    providerName: String,
    acceptedRoles: Set<String>,
    algorithm: Algorithm,
    audience: String,
    issuer: String,
    revocationLookup: (String) -> String?
) {
    jwt(providerName) {
        realm = "gatekeeperd"
        verifier(
            JWT
                .require(algorithm)
                .withAudience(audience)
                .withIssuer(issuer)
                .build()
        )
        validate { credential ->
            val role = credential.payload.getClaim("role").asString()
            if (!credential.payload.audience.contains(audience) || role !in acceptedRoles) return@validate null

            when (JwtConfig.revocationStatus(credential.payload.id, revocationLookup)) {
                JwtRevocationStatus.CONFIRMED_NOT_REVOKED -> JWTPrincipal(credential.payload)
                JwtRevocationStatus.REVOKED, JwtRevocationStatus.INVALID -> null
                JwtRevocationStatus.UNAVAILABLE -> {
                    attributes.put(revocationUnavailableKey, true)
                    null
                }
            }
        }
        challenge { _, _ ->
            if (call.attributes.getOrNull(revocationUnavailableKey) == true) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "revocation_check_unavailable", "Authentication is temporarily unavailable")
            } else {
                call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
            }
        }
    }
}

fun Application.configureSecurity() {
    val appSettings = get<AppSettings>()
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
        allowHeader(HttpHeaders.Accept)
        allowCredentials = true
        allowNonSimpleContentTypes = true

        appSettings.application.corsAllowedHosts.forEach { entry ->
            allowHost(entry.host, schemes = entry.schemes)
        }
    }

    authentication {
        configureAdminJwt(
            JwtConfig.algorithm(appSettings.auth),
            appSettings.auth.audience,
            appSettings.auth.issuer,
            RedisService::getRequired
        )
    }
}
