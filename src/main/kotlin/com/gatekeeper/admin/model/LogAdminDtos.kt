package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class AdminLogEntry(
    val id: String,
    val timestamp: String,
    val level: String,
    val logger: String? = null,
    val thread: String? = null,
    val message: String,
    val requestId: String? = null,
    val stackTrace: String? = null
)

@Serializable
data class AdminLogSummary(
    val scanned: Int,
    val errorLogs: Int,
    val warningLogs: Int,
    val requestCount: Int,
    val successfulRequests: Int,
    val failedRequests: Int,
    val successRate: Double? = null,
    val errorRate: Double? = null
)

@Serializable
data class AdminLogsPage(
    val logs: List<AdminLogEntry>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
    val truncated: Boolean,
    val summary: AdminLogSummary
)

