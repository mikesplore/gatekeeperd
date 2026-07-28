package com.gatekeeper.gate

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
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing project slug"))
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
                        "backend" -> {
                            call.response.header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            call.respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(projectName, paymentLink))
                        }
                        else -> {
                            call.respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(projectName, paymentLink))
                        }
                    }
                }
                is GateResult.Unknown -> {
                    // Unknown project — fail closed (misconfiguration)
                    call.response.header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    call.respond(HttpStatusCode.PaymentRequired, """{"error":"unknown_project","message":"The requested project is not recognized."}""")
                }
            }
        }
    }
}