package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.gate.GateApplicationService
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
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
            val service = call.application.get<GateApplicationService>()
            val project = service.findProject(slug)
            if (project == null) {
                call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                return@get
            }
            val gateResult = service.check(slug)
            if (gateResult !is GateResult.Blocked) {
                call.respondError(HttpStatusCode.BadRequest, "project_active", "This project is not suspended")
                return@get
            }
            call.response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
            call.respond(
                HttpStatusCode.PaymentRequired,
                PaywallTemplates.htmlPaywall(
                    PaywallInfo.from(project, call.application.get<ProjectBalanceAdapter>().financials(project)), payEnabled = true,
                    paymentMethods = call.application.get<GetPaymentMethodAvailability>()(project.currency)
                )
            )
        }

    }
}
