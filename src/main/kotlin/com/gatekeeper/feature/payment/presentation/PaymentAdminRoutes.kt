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
import java.time.LocalDate
import java.util.UUID
import java.math.BigDecimal
import java.time.LocalDateTime
import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentEvents
import com.gatekeeper.feature.payment.domain.usecase.ListPayments
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayment
import com.gatekeeper.feature.payment.domain.usecase.RecordCashPayment
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentRevenue
import com.gatekeeper.feature.payment.domain.usecase.ListProjectPayments
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.HandlePaystackWebhook
import com.gatekeeper.feature.payment.domain.usecase.ReplayPaystackEvent
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentEventForReplay
import com.gatekeeper.feature.payment.presentation.dto.PaystackWebhookPayload
import org.koin.ktor.ext.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CaptureCashPaymentRequest(
    val amount: Double,
    val currency: String? = null,
    val paidAt: String? = null,
    val receiptNumber: String? = null,
    val notes: String? = null
)

@Serializable
data class InitializePaymentRequest(val email: String, val amount: Double? = null)

@Serializable
data class InitializePaymentResponse(val payment_link: String)

@Serializable
data class ProjectPaymentsPageResponse(val payments: List<PaymentResponse>, val total: Long, val limit: Int, val offset: Int, val hasMore: Boolean)

fun Application.configurePaymentAdminRoutes() {
    routing {
        authenticate("auth-jwt") {
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
                    onSuccess = { result -> call.respond(mapOf("id" to result.id.toString(), "reconciled" to result.reconciled, "gatewayStatus" to result.status)) },
                    onFailure = { error ->
                        if (error.message == "Payment not found") call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
                        else call.respondError(HttpStatusCode.BadGateway, "payment_reconciliation_failed", "Payment could not be reconciled")
                    }
                )
            }

            post("/api/admin/projects/{slug}/payments/cash") {
                val slug = call.parameters["slug"]
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_project_slug", "Project slug is required")
                val request = call.receive<CaptureCashPaymentRequest>()
                if (request.amount <= 0.0 || !request.amount.isFinite()) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be greater than zero")
                }
                val paidAt = request.paidAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "unknown"
                call.application.get<RecordCashPayment>()(
                    RecordCashPayment.Command(slug, BigDecimal.valueOf(request.amount), request.currency, paidAt, request.receiptNumber, request.notes, actor)
                ).fold(
                    onSuccess = { payment -> call.respond(HttpStatusCode.Created, mapOf("provider" to payment.provider, "reference" to payment.providerReference, "status" to payment.status)) },
                    onFailure = { error ->
                        val code = if (error.message == "Project not found") "project_not_found" else "payment_capture_rejected"
                        val status = if (error.message == "Project not found") HttpStatusCode.NotFound else HttpStatusCode.BadRequest
                        call.respondError(status, code, error.message ?: "Cash payment was rejected")
                    }
                )
            }

            get("/api/admin/projects/{slug}/payments") {
                val slug = call.parameters["slug"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 25).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                call.application.get<ListProjectPayments>()(ListProjectPayments.Command(slug, limit, offset)).fold(
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

            post("/api/admin/projects/{slug}/payment/initialize") {
                val slug = call.parameters["slug"]
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
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
                    InitiatePayment.Command("paystack", slug, requestedAmount, email = email, currency = "")
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

private fun PaymentWithProject.toAdminResponse() = PaymentAdminResponse(
    id = payment.id.toString(),
    projectId = payment.projectId.toString(),
    projectName = projectName,
    projectSlug = projectSlug,
    provider = payment.provider.lowercase(),
    providerReference = payment.providerReference,
    paystackReference = payment.providerReference,
    amount = payment.amount.toDouble(),
    gatewayStatus = payment.status,
    verifiedVia = payment.verifiedVia,
    paidAt = payment.paidAt?.toString(),
    createdAt = payment.createdAt?.toString().orEmpty()
)
