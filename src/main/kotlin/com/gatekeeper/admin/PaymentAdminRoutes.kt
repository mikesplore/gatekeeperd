package com.gatekeeper.admin

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.dto.*
import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.PaymentEventRepository
import com.gatekeeper.db.repositories.ProjectRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.request.receive
import io.ktor.server.routing.*
import java.time.LocalDate
import com.gatekeeper.plugins.Metrics
import com.gatekeeper.payments.PaymentReconciliationService
import java.util.UUID
import java.math.BigDecimal
import java.time.LocalDateTime
import com.gatekeeper.payments.PaymentApplicationService
import com.gatekeeper.payments.PaymentProvider
import kotlinx.serialization.Serializable

@Serializable
data class CaptureCashPaymentRequest(
    val amount: Double,
    val currency: String? = null,
    val paidAt: String? = null,
    val receiptNumber: String? = null,
    val notes: String? = null
)

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

                val (rows, total) = PaymentRepository.findAllFiltered(status, projectSlug, from, to, limit, offset)
                call.respond(
                    PaymentsListResponse(
                        payments = rows.map { it.toAdminResponse() },
                        total = total,
                        limit = limit,
                        offset = offset
                    )
                )
            }

            post("/api/admin/payments/{id}/reconcile") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_payment_id", "Invalid payment ID")
                val payment = PaymentRepository.findById(id)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "payment_not_found", "Payment not found")
                val reconciled = PaymentReconciliationService.reconcile(payment)
                call.respond(mapOf("id" to id.toString(), "reconciled" to reconciled, "gatewayStatus" to PaymentRepository.findById(id)?.gatewayStatus))
            }

            post("/api/admin/projects/{slug}/payments/cash") {
                val slug = call.parameters["slug"]
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_project_slug", "Project slug is required")
                val project = ProjectRepository.findBySlug(slug)
                    ?: return@post call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                val request = call.receive<CaptureCashPaymentRequest>()
                if (request.amount <= 0.0 || !request.amount.isFinite()) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be greater than zero")
                }
                val paidAt = request.paidAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                    ?: LocalDateTime.now()
                val actor = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.payload?.subject ?: "unknown"
                val reference = request.receiptNumber?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { "cash-$it" }
                    ?: "cash-${UUID.randomUUID()}"
                val notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                val applied = PaymentApplicationService.applySuccessfulPayment(
                    provider = PaymentProvider.CASH,
                    reference = reference,
                    projectSlug = slug,
                    amount = BigDecimal.valueOf(request.amount),
                    currency = request.currency ?: project.currency,
                    verifiedVia = "manual_cash",
                    paidAt = paidAt,
                    rawPayload = notes,
                    actor = actor
                )
                if (!applied) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "payment_capture_rejected", "Cash payment exceeds the project's remaining balance or has an invalid currency")
                }
                call.respond(HttpStatusCode.Created, mapOf("provider" to "cash", "reference" to reference, "status" to "success"))
            }

            get("/api/admin/metrics") {
                call.respond(Metrics.snapshot())
            }

            get("/api/admin/payment-events") {
                val status = call.request.queryParameters["status"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                call.respond(
                    PaymentEventRepository.findByStatus(status, limit).map { event ->
                        PaymentEventAdminResponse(
                            id = event.id.toString(),
                            dedupeKey = event.dedupeKey,
                            eventType = event.eventType,
                            paystackReference = event.reference,
                            processingStatus = event.processingStatus,
                            processingAttempts = event.processingAttempts,
                            processingError = event.processingError,
                            receivedAt = event.receivedAt.toString(),
                            processedAt = event.processedAt?.toString()
                        )
                    }
                )
            }

            get("/api/admin/projects/overdue") {
                val overdue = ProjectRepository.findOverdue(LocalDate.now()).map { it.toOverdueResponse() }
                call.respond(overdue)
            }

            get("/api/admin/revenue") {
                val months = call.request.queryParameters["months"]?.toIntOrNull()?.coerceIn(1, 24) ?: 6
                val (thisMonth, lastMonth) = PaymentRepository.revenueTotals()
                val byMonth = PaymentRepository.revenueByMonth(months)
                val currency = ProjectRepository.findAll().firstOrNull()?.currency ?: "KES"
                call.respond(
                    RevenueReportResponse(
                        totalThisMonth = thisMonth.toDouble(),
                        totalLastMonth = lastMonth.toDouble(),
                        currency = currency,
                        byMonth = byMonth.map { RevenueMonthResponse(it.month, it.amount.toDouble()) }
                    )
                )
            }
        }
    }
}

private fun PaymentRepository.PaymentWithProject.toAdminResponse() = PaymentAdminResponse(
    id = payment.id.toString(),
    projectId = payment.projectId.toString(),
    projectName = projectName,
    projectSlug = projectSlug,
    provider = payment.provider.name.lowercase(),
    providerReference = payment.providerReference,
    paystackReference = payment.paystackReference,
    amount = payment.amount.toDouble(),
    gatewayStatus = payment.gatewayStatus,
    verifiedVia = payment.verifiedVia,
    paidAt = payment.paidAt?.toString(),
    createdAt = payment.createdAt.toString()
)

private fun ProjectRepository.OverdueProject.toOverdueResponse() = OverdueProjectResponse(
    slug = slug,
    name = name,
    clientName = clientName,
    clientEmail = clientEmail,
    dueDate = dueDate.toString(),
    daysOverdue = daysOverdue,
    gracePeriodDays = gracePeriodDays,
    willAutoBlockOn = willAutoBlockOn.toString(),
    amountDue = amountDue?.toDouble() ?: 0.0
)
