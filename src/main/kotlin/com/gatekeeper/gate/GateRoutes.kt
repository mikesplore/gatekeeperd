package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

fun Application.configureGateRoutes() {
    val integrationSettings = get<AppSettings>().integrations
    val gateService = get<GateApplicationService>()
    routing {
        get("/api/gate/check") {
            val slug = call.requireProjectSlug() ?: return@get
            when (val result = gateService.check(slug, call.requestedDomain())) {
                is GateResult.Active -> call.respond(HttpStatusCode.OK, "")
                is GateResult.Blocked -> call.respondBlocked(gateService, result)
                is GateResult.Unknown -> call.respondError(
                    HttpStatusCode.PaymentRequired,
                    "unknown_project",
                    "The requested project is not recognized."
                )
            }
        }

        get("/api/gate/auth") {
            val slug = call.requireProjectSlug() ?: return@get
            when (gateService.check(slug, call.requestedDomain())) {
                is GateResult.Active -> call.respond(HttpStatusCode.OK, "")
                is GateResult.Blocked, is GateResult.Unknown -> call.respond(HttpStatusCode.Forbidden, "")
            }
        }

        get("/api/gate/paywall") {
            val slug = call.requireProjectSlug() ?: return@get
            val gateResult = gateService.check(slug, call.requestedDomain())
            if (gateResult !is GateResult.Blocked || gateResult.paywall == null) {
                call.respondError(HttpStatusCode.BadRequest, "project_active", "This project is not suspended")
                return@get
            }
            val (serviceBillingMode, serviceInvoices) = gateService.serviceInvoices(gateResult.paywall)
            call.response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
            call.respond(
                HttpStatusCode.PaymentRequired,
                PaywallTemplates.htmlBlockWall(
                    gateResult.paywall,
                    serviceInvoices = serviceInvoices,
                    serviceBillingMode = serviceBillingMode,
                    integrationSettings = integrationSettings
                )
            )
        }

    }
}
