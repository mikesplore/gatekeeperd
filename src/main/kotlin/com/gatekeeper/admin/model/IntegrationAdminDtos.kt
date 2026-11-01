package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

// Extracted route DTOs
@Serializable
data class OutboxEventResponse(
    val id: String,
    val eventType: String,
    val idempotencyKey: String,
    val payload: String,
    val attempts: Int,
    val status: String,
    val lastError: String?
)

@Serializable
data class OutboxReplayResponse(
    val replayed: Boolean,
    val id: String,
    val status: String,
    val error: String? = null
)
