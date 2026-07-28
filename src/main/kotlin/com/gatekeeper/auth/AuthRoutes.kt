package com.gatekeeper.auth

import com.gatekeeper.api.respondError
import com.gatekeeper.db.tables.Users
import com.gatekeeper.plugins.JwtConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.auth.AuthRoutes")
private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class UserProfileResponse(
    val email: String,
    val role: String,
    val createdAt: String
)

fun Application.configureAuthRoutes() {
    routing {
        post("/api/auth/login") {
            val body = try {
                json.decodeFromString<LoginRequest>(call.receiveText())
            } catch (e: Exception) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                return@post
            }

            if (body.email.isBlank() || body.password.isBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Email and password are required")
                return@post
            }

            val user = transaction {
                Users.selectAll().where { Users.email eq body.email.lowercase().trim() }
                    .singleOrNull()
            }

            if (user == null) {
                logger.warn("Login attempt for unknown email: ${body.email}")
                call.respondError(HttpStatusCode.Unauthorized, "invalid_credentials", "Invalid email or password")
                return@post
            }

            val passwordHash = user[Users.passwordHash]
            if (!BCrypt.checkpw(body.password, passwordHash)) {
                logger.warn("Failed login attempt for: ${body.email}")
                call.respondError(HttpStatusCode.BadRequest, "invalid_credentials", "Invalid email or password")
                return@post
            }

            val token = JwtConfig.createToken(body.email, user[Users.role])
            logger.info("Successful login: ${body.email} (account created ${user[Users.createdAt]})")
            call.respond(LoginResponse(token))
        }

        authenticate("auth-jwt") {
            get("/api/auth/me") {
                val principal = call.principal<JWTPrincipal>()
                if (principal == null) {
                    call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
                    return@get
                }
                val email = principal.payload.subject

                val user = transaction {
                    Users.selectAll().where { Users.email eq email.lowercase().trim() }
                        .singleOrNull()
                }
                if (user == null) {
                    call.respondError(HttpStatusCode.NotFound, "user_not_found", "User not found")
                    return@get
                }

                call.respond(
                    UserProfileResponse(
                        email = user[Users.email],
                        role = user[Users.role],
                        createdAt = user[Users.createdAt].toString()
                    )
                )
            }
        }
    }
}
