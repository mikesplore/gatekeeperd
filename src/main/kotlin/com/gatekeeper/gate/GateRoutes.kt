package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

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

private fun ApplicationCall.requestedDomain(): String? =
    request.queryParameters["domain"]?.takeIf { it.isNotBlank() }
        ?: request.headers["X-Forwarded-Host"]?.substringBefore(',')?.takeIf { it.isNotBlank() }

private suspend fun ApplicationCall.respondBlocked(result: GateResult.Blocked) {
    val paywall = result.paywall
    if (prefersHtml() && paywall != null) {
        response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
        respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlBlockWall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency)))
        return
    }

    when (result.type) {
        "frontend" -> {
            if (paywall != null) {
                response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlBlockWall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency)))
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
            when (val result = call.application.get<GateApplicationService>().check(slug, call.requestedDomain())) {
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
            when (call.application.get<GateApplicationService>().check(slug, call.requestedDomain())) {
                is GateResult.Active -> call.respond(HttpStatusCode.OK, "")
                is GateResult.Blocked, is GateResult.Unknown -> call.respond(HttpStatusCode.Forbidden, "")
            }
        }

        get("/api/gate/paywall") {
            val slug = call.requireProjectSlug() ?: return@get
            val gateResult = call.application.get<GateApplicationService>().check(slug, call.requestedDomain())
            if (gateResult !is GateResult.Blocked || gateResult.paywall == null) {
                call.respondError(HttpStatusCode.BadRequest, "project_active", "This project is not suspended")
                return@get
            }
            call.response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
            call.respond(
                HttpStatusCode.PaymentRequired,
                PaywallTemplates.htmlBlockWall(
                    gateResult.paywall,
                    payEnabled = true,
                    paymentMethods = call.application.get<GetPaymentMethodAvailability>()(gateResult.paywall.currency)
                )
            )
        }

    }
}
