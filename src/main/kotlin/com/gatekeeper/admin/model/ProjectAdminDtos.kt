package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val domain: String? = null,
    val type: String? = null,
    val billingName: String? = null,
    val billingEmail: String? = null,
    val billingAddress: String? = null,
    val amountDue: Double? = null,
    val currency: String? = null,
    val dueDate: String? = null,
    val gracePeriodDays: Int? = null,
    val deploymentMode: String? = null,
    val serviceMode: String? = null,
    val lifecycleStatus: String? = null,
    val customerId: String? = null
)

@Serializable
data class CreateProjectAdjustmentRequest(
    val type: String,
    val amount: Double,
    val reason: String
)

@Serializable
data class ProjectAdjustmentResponse(
    val id: String,
    val projectId: String,
    val type: String,
    val amount: Double,
    val reason: String,
    val actor: String,
    val createdAt: String,
    val oldBalance: Double,
    val newBalance: Double
)

@Serializable
data class StatusChangeRequest(
    val reason: String,
    val blockReasonCode: String? = null,
    val blockReasonNote: String? = null
)

@Serializable
data class TransferProjectRequest(
    val deploymentMode: String = "client_hosted",
    val serviceMode: String = "production"
)
