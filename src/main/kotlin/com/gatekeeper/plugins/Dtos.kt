package com.gatekeeper.plugins

import kotlinx.serialization.Serializable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Extracted route DTOs
@Serializable
data class HealthReadinessResponse(
    val status: String,
    val dependencies: HealthDependencyStatus
)

@Serializable
data class HealthDependencyStatus(
    val database: Boolean,
    val redis: Boolean,
    val migrations: Boolean
)
