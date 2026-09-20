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
import io.ktor.server.routing.*
import java.time.LocalDate
import com.gatekeeper.plugins.Metrics
import com.gatekeeper.payments.PaymentReconciliationService
import java.util.UUID

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
