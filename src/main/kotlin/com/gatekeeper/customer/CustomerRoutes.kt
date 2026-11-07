package com.gatekeeper.customer

import com.gatekeeper.config.AppSettings
import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.plugins.RedisUnavailableException
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import com.gatekeeper.integrations.ScribedIntegrationClient
import java.math.BigDecimal


fun Application.configureCustomerRoutes() {
    val settings = get<AppSettings>().integrations
    val customerService = get<CustomerApplicationService>()
    val customerAccess = get<CustomerSupportAccessService>()
    val serviceRepository = get<ServiceRepository>()
    val projects = get<ProjectRepository>()
    val customers = get<CustomerRepository>()
    val invoices = get<ServiceInvoiceApplicationService>()
    val secureCookie = get<AppSettings>().environment == com.gatekeeper.config.AppEnvironment.PROD
    routing {
        post("/api/customer/access/request") {
            val body = runCatching { call.receive<CustomerAccessRequest>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Provide a valid email address")
            val email = body.email.trim().lowercase()
            if (!InputValidators.isValidEmail(email)) return@post call.respondError(
                HttpStatusCode.BadRequest, "invalid_email", "Provide a valid email address"
            )
            val challenge = try { customerAccess.requestCode(email) } catch (_: Exception) {
                return@post call.respondError(HttpStatusCode.ServiceUnavailable, "sign_in_unavailable", "Customer sign-in is temporarily unavailable")
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(CustomerAccessChallenge(challenge, "If an account matches that email, a sign-in code has been sent."))
        }

        post("/api/customer/access/verify") {
            val body = runCatching { call.receive<CustomerAccessVerify>() }.getOrNull()
                ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Enter the emailed sign-in code")
            val token = try { customerAccess.verifyCode(body.challenge, body.code.trim()) } catch (_: Exception) {
                return@post call.respondError(HttpStatusCode.ServiceUnavailable, "sign_in_unavailable", "Customer sign-in is temporarily unavailable")
            } ?: return@post call.respondError(HttpStatusCode.Unauthorized, "invalid_code", "That code is incorrect or has expired")
            call.response.cookies.append(Cookie(
                name = CustomerSupportAccessService.SESSION_COOKIE, value = token,
                maxAge = CustomerSupportAccessService.SESSION_TTL_SECONDS, path = "/",
                secure = secureCookie, httpOnly = true, extensions = mapOf("SameSite" to "Strict")
            ))
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(CustomerAccessSession(true))
        }

        post("/api/customer/access/logout") {
            customerAccess.revokeSession(call.request.cookies[CustomerSupportAccessService.SESSION_COOKIE])
            call.response.cookies.append(Cookie(
                name = CustomerSupportAccessService.SESSION_COOKIE, value = "", maxAge = 0, path = "/",
                secure = secureCookie, httpOnly = true, extensions = mapOf("SameSite" to "Strict")
            ))
            call.respond(CustomerAccessSession(false))
        }

        get("/api/customer/account") {
            val customerId = call.customerId(customerAccess) ?: return@get call.respondError(
                HttpStatusCode.Unauthorized, "customer_session_required", "Sign in to view your account"
            )
            call.response.headers.append(HttpHeaders.CacheControl, "no-store, private")
            val customer = customers.findById(customerId) ?: return@get call.respondError(
                HttpStatusCode.Unauthorized, "customer_not_found", "Customer account is unavailable"
            )
            val ownedProjects = projects.findByCustomerId(customerId)
            val services = ownedProjects.flatMap { project ->
                serviceRepository.listByProjectId(project.id).map { service ->
                    val invoice = invoices.find(service.id)
                    val view = invoice?.let { invoices.view(service.id) }
                    val balance = invoice?.let { invoices.balance(service.id) }
                    CustomerAccountService(
                        project = project.slug,
                        projectName = project.name,
                        serviceId = service.id.toString(),
                        serviceName = service.name,
                        domain = service.domain?.takeIf(String::isNotBlank) ?: project.domain,
                        accessStatus = service.accessStatus,
                        invoiceAmount = invoice?.amount?.toPlainString(),
                        paid = view?.paid?.toPlainString(),
                        balance = balance?.toPlainString(),
                        currency = invoice?.currency ?: project.currency,
                        dueDate = invoice?.dueDate?.toString(),
                        invoiceUrl = invoice?.let { "/api/customer/services/${service.id}/invoice" },
                        paymentUrl = balance?.takeIf { it > BigDecimal.ZERO }?.let {
                            "/api/gate/pay?project=${java.net.URLEncoder.encode(project.slug, Charsets.UTF_8)}&serviceId=${service.id}"
                        },
                        availablePaymentMethods = call.application.get<GetPaymentMethodAvailability>()(invoice?.currency ?: project.currency).let { methods ->
                            listOfNotNull(
                                "paystack".takeIf { methods.paystack },
                                "mpesa".takeIf { methods.mpesa && balance?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true }
                            )
                        }
                    )
                }
            }
            val payments = ownedProjects.flatMap { project ->
                customerService.payments(project.id).map { payment ->
                    val serviceName = payment.serviceId?.let { serviceRepository.findById(it)?.name } ?: project.name
                    CustomerAccountPayment(
                        id = payment.id.toString(), serviceName = serviceName,
                        amount = payment.amount.toPlainString(), currency = payment.currency ?: project.currency,
                        status = payment.status, createdAt = payment.createdAt?.toString(), paidAt = payment.paidAt?.toString(),
                        receiptUrl = payment.takeIf { it.status.equals("success", true) }?.let {
                            "/api/customer/projects/${project.slug}/payments/${it.id}/receipt"
                        }
                    )
                }
            }.sortedByDescending { it.paidAt ?: it.createdAt ?: "" }
            val displayName = listOf(customer.firstName, customer.lastName).filter(String::isNotBlank).joinToString(" ")
            call.respond(CustomerAccountResponse(displayName, customer.contactEmail.orEmpty(), services, payments))
        }

        get("/api/customer/services/{serviceId}/invoice") {
            val customerId = call.customerId(customerAccess) ?: return@get call.respondError(
                HttpStatusCode.Unauthorized, "customer_session_required", "Sign in to view your account"
            )
            val serviceId = call.parameters["serviceId"]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Invalid service ID")
            val service = serviceRepository.findById(serviceId) ?: return@get call.respondError(
                HttpStatusCode.NotFound, "invoice_unavailable", "Invoice not found"
            )
            val project = projects.findById(service.projectId)?.takeIf { it.customerId == customerId }
                ?: return@get call.respondError(HttpStatusCode.NotFound, "invoice_unavailable", "Invoice not found")
            val invoice = invoices.find(serviceId) ?: return@get call.respondError(
                HttpStatusCode.NotFound, "invoice_unavailable", "No invoice has been created for this service"
            )
            val view = invoices.view(serviceId) ?: return@get call.respondError(
                HttpStatusCode.NotFound, "invoice_unavailable", "Invoice not found"
            )
            val adjustments = call.application.get<ProjectBalanceAdapter>().serviceAdjustments(serviceId)
            val billed = adjustments.fold(invoice.amount) { total, adjustment ->
                total + if (adjustment.type == AdjustmentType.ADDITIONAL_CHARGE) adjustment.amount else -adjustment.amount
            }
            val balance = invoices.balance(serviceId) ?: BigDecimal.ZERO
            val items = listOf(ScribedIntegrationClient.InvoiceItem(service.name, amount = invoice.amount.toPlainString())) +
                adjustments.map { adjustment -> ScribedIntegrationClient.InvoiceItem(
                    if (adjustment.type == AdjustmentType.ADDITIONAL_CHARGE) "Additional charge" else "Discount",
                    amount = (if (adjustment.type == AdjustmentType.ADDITIONAL_CHARGE) adjustment.amount else adjustment.amount.negate()).toPlainString()
                ) }
            val (status, bytes) = call.application.get<ScribedIntegrationClient>().generateInvoice(
                project, "${project.name} — ${service.name}", invoice.description,
                billed.max(BigDecimal.ZERO).toPlainString(), invoice.currency, invoice.dueDate,
                serviceId.toString(), invoice.createdAt.toLocalDate(), view.paid.toPlainString(), balance.toPlainString(), items,
                when { balance.signum() == 0 -> "paid"; view.paid.signum() > 0 -> "partially_paid"; else -> "unpaid" }
            )
            if (bytes == null) return@get call.respondError(status, "invoice_download_failed", "Scribed could not generate the service invoice")
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=invoice-${project.slug}-${serviceId}.pdf")
            call.respondBytes(bytes, ContentType.Application.Pdf)
        }

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
            val (status, bytes) = call.application.get<ScribedIntegrationClient>().issueReceipt(
                project, payment, "Payment received for ${project.name}"
            )
            if (bytes == null) return@get call.respondError(
                status, "receipt_download_failed", "Scribed could not issue this receipt"
            )
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=receipt-${project.slug}-${payment.id}.pdf")
            call.respondBytes(bytes, ContentType.Application.Pdf)
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

private suspend fun ApplicationCall.customerId(access: CustomerSupportAccessService): java.util.UUID? {
    return try {
        access.customerIdForSession(request.cookies[CustomerSupportAccessService.SESSION_COOKIE])
    } catch (_: Exception) {
        null
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
