package com.gatekeeper.gate

import com.gatekeeper.api.respondError
import com.gatekeeper.docker.GateResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureGateRoutes() {
    routing {
        get("/api/gate/check") {
            val slug = call.request.queryParameters["project"]
            if (slug.isNullOrBlank()) {
                call.respondError(HttpStatusCode.BadRequest, "missing_project_slug", "Missing project slug query parameter")
                return@get
            }

            when (val result = GateService.check(slug)) {
                is GateResult.Active -> {
                    call.respond(HttpStatusCode.OK, "")
                }
                is GateResult.Blocked -> {
                    val paymentLink: String? = result.paymentLink
                    val projectName = result.projectName ?: slug
                    when (result.type) {
                        "frontend" -> {
                            call.response.header(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                            call.respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlPaywall(projectName, paymentLink))
                        }
                        else -> {
                            call.respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(paymentLink))
                        }
                    }
                }
                is GateResult.Unknown -> {
                    call.respondError(
                        HttpStatusCode.PaymentRequired,
                        "unknown_project",
                        "The requested project is not recognized."
                    )
                }
            }
        }
    }
}
