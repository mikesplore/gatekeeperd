package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.paystack.PaystackClient
import com.gatekeeper.paystack.ProjectPaymentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

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
        respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlPaywall(paywall, payEnabled = true))
        return
    }

    when (result.type) {
        "frontend" -> {
            if (paywall != null) {
                response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlPaywall(paywall, payEnabled = true))
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
            when (val result = GateService.check(slug)) {
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
            when (GateService.check(slug)) {
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
                PaywallTemplates.htmlPaywall(PaywallInfo.from(project), payEnabled = true)
            )
        }

        get("/api/gate/pay") {
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

            ProjectPaymentService.initializeForProject(project).fold(
                onSuccess = { url -> call.respondRedirect(url, permanent = false) },
                onFailure = { err ->
                    call.respondError(
                        HttpStatusCode.BadGateway,
                        "payment_unavailable",
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

            val verified = PaystackClient.verifyTransaction(reference)
                .map { it.status.equals("success", ignoreCase = true) }
                .getOrDefault(false)

            if (verified) {
                logger.info("Payment callback verified for ${project.slug}, ref=$reference")
            } else {
                logger.warn("Payment callback could not be verified for ${project.slug}, ref=$reference")
            }

            call.respondRedirect(projectRedirectUrl(project.domain), permanent = false)
        }
    }
}
