package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.VerifyPayment
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.koin.ktor.ext.get
import java.math.BigDecimal

private val logger = LoggerFactory.getLogger("com.gatekeeper.gate.GateRoutes")

private suspend fun ApplicationCall.requireProjectSlug(): String? {
    val raw = request.queryParameters["project"]
    if (raw.isNullOrBlank()) {
        respondError(HttpStatusCode.BadRequest, "missing_project_slug", "Missing project slug query parameter")
        return null
    }
    val slug = InputValidators.normalizeSlug(raw)
    if (slug == null) {
        respondError(HttpStatusCode.BadRequest, "invalid_project_slug", "Invalid project slug")
        return null
    }
    return slug
}

private fun ApplicationCall.prefersHtml(): Boolean {
    val accept = request.headers[HttpHeaders.Accept].orEmpty()
    return accept.contains("text/html") && !accept.contains("application/json")
}

private fun isSuspended(status: String): Boolean =
    status.lowercase() in listOf("blocked", "manual_block")

private fun projectRedirectUrl(domain: String): String {
    val trimmed = domain.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        else -> "https://$trimmed"
    }
}

private suspend fun ApplicationCall.respondBlocked(result: GateResult.Blocked) {
    val paywall = result.paywall
    if (prefersHtml() && paywall != null) {
        response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
        respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlPaywall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency)))
        return
    }

    when (result.type) {
        "frontend" -> {
            if (paywall != null) {
                response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlPaywall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency)))
            } else {
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(null))
            }
        }
        else -> respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(paywall))
    }
}

fun Application.configureGateRoutes() {
    routing {
        get("/api/gate/check") {
            val slug = call.requireProjectSlug() ?: return@get
            when (val result = call.application.get<GateApplicationService>().check(slug)) {
                is GateResult.Active -> call.respond(HttpStatusCode.OK, "")
                is GateResult.Blocked -> call.respondBlocked(result)
                is GateResult.Unknown -> call.respondError(
                    HttpStatusCode.PaymentRequired,
                    "unknown_project",
                    "The requested project is not recognized."
                )
            }
        }

        get("/api/gate/auth") {
            val slug = call.requireProjectSlug() ?: return@get
            when (call.application.get<GateApplicationService>().check(slug)) {
                is GateResult.Active -> call.respond(HttpStatusCode.OK, "")
                is GateResult.Blocked, is GateResult.Unknown -> call.respond(HttpStatusCode.Forbidden, "")
            }
        }

        get("/api/gate/paywall") {
            val slug = call.requireProjectSlug() ?: return@get
            val project = ProjectRepository.findBySlug(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@get
            }
            if (!isSuspended(project.status)) {
                call.respondError(HttpStatusCode.BadRequest, "project_active", "This project is not suspended")
                return@get
            }
            call.response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
            call.respond(
                HttpStatusCode.PaymentRequired,
                PaywallTemplates.htmlPaywall(
                    PaywallInfo.from(project), payEnabled = true,
                    paymentMethods = call.application.get<GetPaymentMethodAvailability>()(project.currency)
                )
            )
        }

        get("/api/gate/pay") {
            val slug = call.requireProjectSlug() ?: return@get
            val amountText = call.request.queryParameters["amount"]
            val requestedAmount = amountText?.toBigDecimalOrNull()
            if (amountText != null && (requestedAmount == null || requestedAmount <= BigDecimal.ZERO || requestedAmount.scale().coerceAtLeast(0) > 2)) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_payment_amount", "Payment amount must be greater than zero and have at most two decimal places")
                return@get
            }
            val project = ProjectRepository.findBySlug(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@get
            }
            if (!isSuspended(project.status)) {
                call.respondError(HttpStatusCode.BadRequest, "project_active", "This project is not suspended")
                return@get
            }

            call.application.get<InitiatePayment>()(
                InitiatePayment.Command("paystack", project.slug, requestedAmount, currency = project.currency)
            ).fold(
                onSuccess = { result -> call.respondRedirect(result.authorizationUrl ?: "/api/gate/payment/callback?project=${project.slug}&reference=${result.reference}", permanent = false) },
                onFailure = { err ->
                    val failure = err as? InitiatePayment.PaymentInitiationFailure
                    val invalidAmount = failure?.kind == InitiatePayment.FailureKind.INVALID_REQUEST
                    call.respondError(
                        if (invalidAmount) HttpStatusCode.BadRequest else HttpStatusCode.BadGateway,
                        failure?.code ?: "payment_unavailable",
                        err.message ?: "Unable to start payment"
                    )
                }
            )
        }

        get("/api/gate/payment/callback") {
            val slug = call.request.queryParameters["project"]
            val reference = call.request.queryParameters["reference"]
                ?: call.request.queryParameters["trxref"]

            if (slug.isNullOrBlank() || reference.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_callback", "Missing project or payment reference")
                return@get
            }

            val project = ProjectRepository.findBySlug(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@get
            }

            val verification = call.application.get<VerifyPayment>()(
                VerifyPayment.Command("paystack", reference, project.id, "callback")
            ).getOrNull()
            if (verification?.success == true) {
                logger.info("Payment callback verified and applied for ${project.slug}, ref=$reference")
            } else {
                logger.warn("Payment callback could not be verified for ${project.slug}, ref=$reference")
            }

            call.respondRedirect(projectRedirectUrl(project.domain), permanent = false)
        }
    }
}
