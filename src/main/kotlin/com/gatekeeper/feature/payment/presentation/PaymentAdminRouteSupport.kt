package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.api.respondError
import com.gatekeeper.feature.payment.domain.usecase.RecordCashPayment
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import org.koin.ktor.ext.get

internal suspend fun ApplicationCall.captureCashPayment(serviceId: java.util.UUID) {
    val request = receive<CaptureCashPaymentRequest>()
    val actor = principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
    application.get<PaymentAdminApplicationService>().captureCashPayment(
        serviceId, request.amount, request.currency, request.paidAt, request.receiptNumber, request.notes, actor
    ).fold(
        onSuccess = { payment ->
            respond(HttpStatusCode.Created, mapOf("provider" to payment.provider, "reference" to payment.providerReference, "status" to payment.status))
        },
        onFailure = { error ->
            val missingService = error.message == "Service not found"
            respondError(
                if (missingService) HttpStatusCode.NotFound else HttpStatusCode.BadRequest,
                if (missingService) "service_not_found" else "payment_capture_rejected",
                error.message ?: "Cash payment was rejected"
            )
        }
    )
}
