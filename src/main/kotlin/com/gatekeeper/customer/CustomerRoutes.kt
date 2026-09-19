package com.gatekeeper.customer

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.tables.SupportRequests
import com.gatekeeper.paystack.ProjectPaymentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class CustomerPaymentResponse(
    val id: String,
    val reference: String,
    val amount: Double,
    val currency: String,
    val status: String,
    val paidAt: String? = null,
    val receiptUrl: String
)

@Serializable
data class CustomerStatusResponse(
    val project: String,
    val name: String,
    val domain: String,
    val accessStatus: String,
    val blockReason: String? = null,
    val amountDue: Double? = null,
    val currency: String,
    val dueDate: String? = null,
    val paymentUrl: String,
    val portalUrl: String,
    val supportUrl: String,
    val payments: List<CustomerPaymentResponse>
)

@Serializable
data class SupportRequestBody(
    val name: String? = null,
    val email: String,
    val message: String
)

@Serializable
data class SupportRequestResponse(val id: String, val status: String, val message: String)

@Serializable
data class CustomerReminderResponse(
    val recipient: String,
    val subject: String,
    val message: String,
    val paymentUrl: String,
    val frontendUrl: String? = null
)

private fun frontendUrl(path: String): String? {
    val base = AppConfig.frontendBaseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
    return "$base/${path.trimStart('/')}"
}

fun Application.configureCustomerRoutes() {
    routing {
        get("/api/customer/projects/{slug}/status") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val project = ProjectRepository.findBySlug(slug) ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val payments = PaymentRepository.findByProjectId(project.id).map { payment ->
                CustomerPaymentResponse(
                    id = payment.id.toString(), reference = payment.paystackReference,
                    amount = payment.amount.toDouble(), currency = project.currency,
                    status = payment.gatewayStatus, paidAt = payment.paidAt?.toString(),
                    receiptUrl = "/api/customer/projects/$slug/payments/${payment.id}/receipt"
                )
            }
            call.respond(CustomerStatusResponse(
                project = project.slug, name = project.name, domain = project.domain,
                accessStatus = project.status, blockReason = project.blockReason,
                amountDue = project.amountDue?.toDouble(), currency = project.currency,
                dueDate = project.dueDate?.toString(), paymentUrl = "/api/gate/pay?project=$slug",
                portalUrl = frontendUrl("portal/$slug") ?: "/api/customer/projects/$slug/status",
                supportUrl = "/api/customer/projects/$slug/support", payments = payments
            ))
        }

        get("/api/customer/projects/{slug}/payments/{paymentId}/receipt") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val paymentId = call.parameters["paymentId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_payment_id", "Invalid payment ID")
            val project = ProjectRepository.findBySlug(slug) ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val payment = PaymentRepository.findById(paymentId)?.takeIf { it.projectId == project.id }
                ?: return@get call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
            if (payment.gatewayStatus != "success") return@get call.respondError(HttpStatusCode.Conflict, "payment_not_complete", "A receipt is available after successful payment")
            call.respond(mapOf(
                "receiptNumber" to payment.paystackReference,
                "project" to project.name,
                "domain" to project.domain,
                "amount" to payment.amount.toDouble(),
                "currency" to project.currency,
                "paidAt" to payment.paidAt?.toString(),
                "reference" to payment.paystackReference
            ))
        }

        get("/api/customer/projects/{slug}/portal") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            if (ProjectRepository.findBySlug(slug) == null) return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val url = frontendUrl("portal/$slug") ?: return@get call.respondError(HttpStatusCode.ServiceUnavailable, "frontend_not_configured", "Customer frontend URL is not configured")
            call.respondRedirect(url, permanent = false)
        }

        get("/api/customer/projects/{slug}/payment-success") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            if (ProjectRepository.findBySlug(slug) == null) return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val url = frontendUrl("payment/success?project=$slug&reference=${call.request.queryParameters["reference"].orEmpty()}")
                ?: return@get call.respondError(HttpStatusCode.ServiceUnavailable, "frontend_not_configured", "Customer frontend URL is not configured")
            call.respondRedirect(url, permanent = false)
        }

        get("/api/customer/projects/{slug}/reminder") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val project = ProjectRepository.findBySlug(slug) ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val recipient = project.clientEmail?.takeIf { it.isNotBlank() }
                ?: return@get call.respondError(HttpStatusCode.Conflict, "missing_client_email", "This project has no client email configured")
            call.respond(CustomerReminderResponse(
                recipient = recipient,
                subject = "Payment required for ${project.name}",
                message = "Access to ${project.domain} is currently ${project.status}. Please complete the outstanding payment to restore access.",
                paymentUrl = "/api/gate/pay?project=$slug",
                frontendUrl = frontendUrl("payment/$slug")
            ))
        }

        post("/api/customer/projects/{slug}/support") {
            val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val project = ProjectRepository.findBySlug(slug) ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val body = runCatching { call.receive<SupportRequestBody>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Support request body is invalid")
            if (!InputValidators.isValidEmail(body.email) || body.message.isBlank() || body.message.length > 5000) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Provide a valid email and a message of 1–5000 characters")
            }
            val id = transaction {
                SupportRequests.insert {
                    it[projectId] = project.id
                    it[requesterName] = body.name?.trim()?.takeIf { name -> name.isNotBlank() }
                    it[requesterEmail] = body.email.trim().lowercase()
                    it[message] = body.message.trim()
                }[SupportRequests.id]
            }
            call.respond(HttpStatusCode.Accepted, SupportRequestResponse(id.toString(), "open", "Your support request has been received."))
        }
    }
}
