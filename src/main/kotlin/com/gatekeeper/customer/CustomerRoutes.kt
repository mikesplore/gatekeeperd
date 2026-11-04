package com.gatekeeper.customer

import com.gatekeeper.config.AppSettings
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.plugins.RedisUnavailableException
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get







fun Application.configureCustomerRoutes() {
    val settings = get<AppSettings>().integrations
    val customerService = get<CustomerApplicationService>()
    val customerAccess = get<CustomerSupportAccessService>()
    val serviceRepository = get<ServiceRepository>()
    routing {
        get("/api/customer/projects/{slug}/status") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val lookup = call.ownedCustomerProject(slug, customerAccess, customerService)
            if (lookup.unavailable) return@get call.respondError(HttpStatusCode.ServiceUnavailable, "customer_access_unavailable", "Customer access is temporarily unavailable")
            val project = lookup.project ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val paymentService = serviceRepository.listByProjectId(project.id).firstOrNull { it.isDefault }
                ?: serviceRepository.listByProjectId(project.id).firstOrNull()
            val paymentUrl = paymentService?.let { "/api/gate/pay?project=$slug&serviceId=${it.id}" }.orEmpty()
            val payments = customerService.payments(project.id).map { payment ->
                CustomerPaymentResponse(
                    id = payment.id.toString(), reference = payment.providerReference,
                    amount = payment.amount.toDouble(), currency = project.currency,
                    status = payment.status, paidAt = payment.paidAt?.toString(),
                    receiptUrl = "/api/customer/projects/$slug/payments/${payment.id}/receipt"
                )
            }
            call.respond(CustomerStatusResponse(
                project = project.slug, name = project.name, domain = project.domain,
                accessStatus = project.status, blockReason = project.blockReason,
                amountDue = project.amountDue?.toDouble(), currency = project.currency,
                dueDate = project.dueDate?.toString(), paymentUrl = paymentUrl,
                portalUrl = frontendUrl("portal/$slug", settings) ?: "/api/customer/projects/$slug/status",
                supportUrl = "/api/customer/projects/$slug/support", payments = payments
            ))
        }

        get("/api/customer/projects/{slug}/payments/{paymentId}/receipt") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val paymentId = call.parameters["paymentId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_payment_id", "Invalid payment ID")
            val lookup = call.ownedCustomerProject(slug, customerAccess, customerService)
            if (lookup.unavailable) return@get call.respondError(HttpStatusCode.ServiceUnavailable, "customer_access_unavailable", "Customer access is temporarily unavailable")
            val project = lookup.project ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val payment = customerService.payment(paymentId)?.takeIf { it.projectId == project.id }
                ?: return@get call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
            if (payment.status != "success") return@get call.respondError(HttpStatusCode.Conflict, "payment_not_complete", "A receipt is available after successful payment")
            call.respond(CustomerReceiptResponse(
                receiptNumber = payment.providerReference,
                project = project.name,
                domain = project.domain,
                amount = payment.amount.toDouble(),
                currency = project.currency,
                paidAt = payment.paidAt?.toString(),
                reference = payment.providerReference
            ))
        }

        get("/api/customer/projects/{slug}/portal") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            if (customerService.project(slug) == null) return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val url = frontendUrl("portal/$slug", settings) ?: return@get call.respondError(HttpStatusCode.ServiceUnavailable, "frontend_not_configured", "Customer frontend URL is not configured")
            call.respondRedirect(url, permanent = false)
        }

        get("/api/customer/projects/{slug}/payment-success") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            if (customerService.project(slug) == null) return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val url = frontendUrl("payment/success?project=$slug&reference=${call.request.queryParameters["reference"].orEmpty()}", settings)
                ?: return@get call.respondError(HttpStatusCode.ServiceUnavailable, "frontend_not_configured", "Customer frontend URL is not configured")
            call.respondRedirect(url, permanent = false)
        }

        get("/api/customer/projects/{slug}/reminder") {
            val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val lookup = call.ownedCustomerProject(slug, customerAccess, customerService)
            if (lookup.unavailable) return@get call.respondError(HttpStatusCode.ServiceUnavailable, "customer_access_unavailable", "Customer access is temporarily unavailable")
            val project = lookup.project ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val recipient = project.customerEmail?.takeIf { it.isNotBlank() }
                ?: return@get call.respondError(HttpStatusCode.Conflict, "missing_customer_email", "This project has no customer email configured")
            val paymentService = serviceRepository.listByProjectId(project.id).firstOrNull { it.isDefault }
                ?: serviceRepository.listByProjectId(project.id).firstOrNull()
                ?: return@get call.respondError(HttpStatusCode.Conflict, "missing_customer_service", "This project has no billable service configured")
            call.respond(CustomerReminderResponse(
                recipient = recipient,
                subject = "Payment required for ${project.name}",
                message = "Access to ${project.domain} is currently ${project.status}. Please complete the outstanding payment to restore access.",
                paymentUrl = "/api/gate/pay?project=$slug&serviceId=${paymentService.id}",
                frontendUrl = frontendUrl("payment/$slug", settings)
            ))
        }

        post("/api/customer/projects/{slug}/support") {
            val slug = call.parameters["slug"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing project slug")
            val project = customerService.project(slug) ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
            val body = runCatching { call.receive<SupportRequestBody>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Support request body is invalid")
            if (!InputValidators.isValidEmail(body.email) || body.message.isBlank() || body.message.length > 5000) {
                return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Provide a valid email and a message of 1–5000 characters")
            }
            val id = customerService.createSupportRequest(
                project.id,
                body.name?.trim()?.takeIf { it.isNotBlank() },
                body.email.trim().lowercase(),
                body.message.trim()
            )
            call.respond(HttpStatusCode.Accepted, SupportRequestResponse(id.toString(), "open", "Your support request has been received."))
        }
    }
}

private data class CustomerProjectLookup(val project: ProjectRecord? = null, val unavailable: Boolean = false)

private suspend fun ApplicationCall.ownedCustomerProject(
    slug: String,
    access: CustomerSupportAccessService,
    customerService: CustomerApplicationService
): CustomerProjectLookup {
    val customerId = try {
        access.customerIdForSession(request.cookies[CustomerSupportAccessService.SESSION_COOKIE])
    } catch (_: RedisUnavailableException) {
        return CustomerProjectLookup(unavailable = true)
    } catch (_: Exception) {
        return CustomerProjectLookup(unavailable = true)
    } ?: return CustomerProjectLookup()
    val project = customerService.project(slug)?.takeIf { it.customerId == customerId }
    if (project != null) response.headers.append(HttpHeaders.CacheControl, "no-store, private")
    return CustomerProjectLookup(project)
}
