package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.dto.*
import com.gatekeeper.api.respondError
import com.gatekeeper.api.dto.PaginatedResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import java.util.UUID
import java.math.BigDecimal
import java.time.LocalDateTime
import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentEvents
import com.gatekeeper.feature.payment.domain.usecase.ListPayments
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayment
import com.gatekeeper.feature.payment.domain.usecase.RecordCashPayment
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentRevenue
import com.gatekeeper.feature.payment.domain.repository.ServiceMembershipRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.payment.domain.usecase.ListProjectPayments
import com.gatekeeper.feature.payment.domain.usecase.ListServicePayments
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.HandlePaystackWebhook
import com.gatekeeper.feature.payment.domain.usecase.ReplayPaystackEvent
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentEventForReplay
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.payment.presentation.dto.PaystackWebhookPayload
import org.koin.ktor.ext.get
import kotlinx.serialization.json.Json






fun Application.configurePaymentAdminRoutes() {
    routing {
        authenticate("auth-admin") {
            get("/api/admin/payments") {
                val status = call.request.queryParameters["status"]
                val projectSlug = call.request.queryParameters["project_slug"]
                val from = InputValidators.parseDueDate(call.request.queryParameters["from"])
                val to = InputValidators.parseDueDate(call.request.queryParameters["to"])
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

                val page = call.application.get<ListPayments>()(ListPayments.Command(status, projectSlug, from, to, limit, offset))
                call.respond(
                    PaymentsListResponse(
                        payments = page.items.map { it.toAdminResponse() },
                        total = page.total,
                        limit = limit,
                        offset = offset
                    )
                )
            }

            post("/api/admin/payments/{id}/reconcile") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_payment_id", "Invalid payment ID")
                call.application.get<ReconcilePayment>()(id).fold(
                    onSuccess = { result ->
                        call.respond(ReconcilePaymentResponse(result.id.toString(), result.reconciled, result.status))
                    },
                    onFailure = { error ->
                        if (error.message == "Payment not found") call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
                        else call.respondError(HttpStatusCode.BadGateway, "payment_reconciliation_failed", "Payment could not be reconciled")
                    }
                )
            }

            post("/api/admin/services/{serviceId}/payments/cash") {
                val serviceId = runCatching { UUID.fromString(call.parameters["serviceId"]) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                call.captureCashPayment(serviceId)
            }

            post("/api/admin/projects/{projectId}/services/{serviceId}/payments/cash") {
                val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is invalid")
                val serviceId = runCatching { UUID.fromString(call.parameters["serviceId"]) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                if (call.application.get<ServiceRepository>().findByProjectAndId(projectId, serviceId) == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found for this project")
                }
                call.captureCashPayment(serviceId)
            }

            get("/api/admin/projects/{projectId}/payments") {
                val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is invalid")
                val project = call.application.get<ProjectRepository>().findById(projectId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 25).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                call.application.get<ListProjectPayments>()(ListProjectPayments.Command(project.slug, limit, offset)).fold(
                    onSuccess = { page ->
                        call.respond(ProjectPaymentsPageResponse(page.items.map { it.toResponse() }, page.total, limit, offset, offset + page.items.size < page.total))
                    },
                    onFailure = { error ->
                        val missing = error.message == "Project not found"
                        call.respondError(if (missing) HttpStatusCode.NotFound else HttpStatusCode.InternalServerError,
                            if (missing) "project_not_found" else "payment_list_failed", error.message ?: "Unable to load project payments")
                    }
                )
            }

            get("/api/admin/projects/{projectId}/services/{serviceId}/payments") {
                val projectId = runCatching { UUID.fromString(call.parameters["projectId"]) }.getOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_project_id", "Project ID is invalid")
                val serviceId = runCatching { UUID.fromString(call.parameters["serviceId"]) }.getOrNull()
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                if (!call.application.get<ServiceMembershipRepository>().exists(projectId, serviceId)) {
                    return@get call.respondError(HttpStatusCode.NotFound, "service_not_found", "Service not found")
                }
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 25).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val page = call.application.get<ListServicePayments>()(ListServicePayments.Command(serviceId, limit, offset))
                call.respond(ProjectPaymentsPageResponse(page.items.map { it.toResponse() }, page.total, limit, offset, offset + page.items.size < page.total))
            }

            post("/api/admin/projects/{slug}/services/{serviceId}/payment/initialize") {
                val slug = call.parameters["slug"]
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                val serviceId = runCatching { UUID.fromString(call.parameters["serviceId"]) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_service_id", "Service ID is invalid")
                val body = runCatching { call.receive<InitializePaymentRequest>() }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Email is required")
                val email = body.email.trim().ifBlank { null }
                if (email != null && !com.gatekeeper.api.InputValidators.isValidEmail(email)) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_request", "email is not a valid email address")
                }
                val requestedAmount = body.amount?.let {
                    if (!it.isFinite() || it <= 0.0) return@post call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be greater than zero")
                    BigDecimal.valueOf(it)
                }
                call.application.get<InitiatePayment>()(
                    InitiatePayment.Command("paystack", slug, requestedAmount, email = email, currency = "", serviceId = serviceId)
                ).fold(
                    onSuccess = { initiated -> call.respond(InitializePaymentResponse(initiated.authorizationUrl.orEmpty())) },
                    onFailure = { error ->
                        val failure = error as? InitiatePayment.PaymentInitiationFailure
                        val status = when (failure?.kind) {
                            InitiatePayment.FailureKind.NOT_FOUND -> HttpStatusCode.NotFound
                            InitiatePayment.FailureKind.INVALID_REQUEST -> HttpStatusCode.BadRequest
                            else -> HttpStatusCode.BadGateway
                        }
                        call.respondError(status, failure?.code ?: "paystack_error", error.message ?: "Failed to initialize payment")
                    }
                )
            }

            get("/api/admin/payment-events") {
                val status = call.request.queryParameters["status"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val provider = call.request.queryParameters["provider"]?.lowercase()?.takeIf { it.isNotBlank() && it != "all" }
                if (provider != null && provider !in setOf("paystack", "mpesa")) {
                    return@get call.respondError(HttpStatusCode.BadRequest, "invalid_payment_provider", "Provider must be paystack or mpesa")
                }
                val page = call.application.get<ListPaymentEvents>()(status, provider, limit, offset)
                val events = page.items.map { event ->
                        PaymentEventAdminResponse(
                            id = event.id.toString(),
                            dedupeKey = event.dedupeKey,
                            eventType = event.eventType,
                            provider = event.provider,
                            paystackReference = event.reference,
                            processingStatus = event.processingStatus,
                            processingAttempts = event.processingAttempts,
                            processingError = event.processingError,
                            receivedAt = event.receivedAt.toString(),
                            processedAt = event.processedAt?.toString()
                        )
                    }
                val total = page.total
                call.respond(PaginatedResponse(events, total, limit, offset, offset + events.size < total))
            }

            post("/api/admin/payment-events/{id}/replay") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_event_id", "Invalid payment event ID")
                val event = call.application.get<GetPaymentEventForReplay>()(id)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "payment_event_not_found", "Payment event not found")
                val payload = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<PaystackWebhookPayload>(event.rawPayload) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.UnprocessableEntity, "replay_failed", "Payment event replay failed integrity or processing checks")
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "admin"
                val result = call.application.get<ReplayPaystackEvent>()(
                    id,
                    HandlePaystackWebhook.Command(
                        eventType = payload.event, paymentStatus = payload.data.status,
                        reference = payload.data.reference, projectSlug = payload.data.metadata["project_slug"],
                        amountKobo = payload.data.amount, currency = payload.data.currency,
                        rawPayload = event.rawPayload, actor = actor
                    )
                )
                when (result) {
                    ReplayPaystackEvent.Result.NOT_FOUND -> return@post call.respondError(HttpStatusCode.NotFound, "payment_event_not_found", "Payment event not found")
                    ReplayPaystackEvent.Result.NOT_FAILED -> return@post call.respondError(HttpStatusCode.Conflict, "event_not_failed", "Only failed payment events can be replayed")
                    ReplayPaystackEvent.Result.REPLAY_FAILED -> return@post call.respondError(HttpStatusCode.UnprocessableEntity, "replay_failed", "Payment event replay failed integrity or processing checks")
                    ReplayPaystackEvent.Result.REPLAYED -> Unit
                }
                call.respond(mapOf("status" to "replayed", "eventId" to id.toString()))
            }

            get("/api/admin/revenue") {
                val months = call.request.queryParameters["months"]?.toIntOrNull()?.coerceIn(1, 24) ?: 6
                val report = call.application.get<GetPaymentRevenue>()(months)
                call.respond(
                    RevenueReportResponse(
                        totalThisMonth = report.thisMonth.toDouble(),
                        totalLastMonth = report.lastMonth.toDouble(),
                        currency = report.currency,
                        byMonth = report.byMonth.map { RevenueMonthResponse(it.month, it.amount.toDouble()) },
                        totalPayments = report.counts.total,
                        successfulPayments = report.counts.successful,
                        pendingPayments = report.counts.pending,
                        failedPayments = report.counts.failed
                    )
                )
            }
        }
    }
}
