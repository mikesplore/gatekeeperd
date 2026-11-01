package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get

internal suspend fun ApplicationCall.requireProjectSlug(): String? {
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

internal fun ApplicationCall.prefersHtml(): Boolean {
    val accept = request.headers[HttpHeaders.Accept].orEmpty()
    return accept.contains("text/html") && !accept.contains("application/json")
}

internal fun ApplicationCall.requestedDomain(): String? =
    request.queryParameters["domain"]?.takeIf { it.isNotBlank() }
        ?: request.headers["X-Forwarded-Host"]?.substringBefore(',')?.takeIf { it.isNotBlank() }

internal suspend fun ApplicationCall.respondBlocked(gateService: GateApplicationService, result: GateResult.Blocked) {
    val integrationSettings = application.get<AppSettings>().integrations
    val paywall = result.paywall
    if (prefersHtml() && paywall != null) {
        val (serviceBillingMode, serviceInvoices) = gateService.serviceInvoices(paywall)
        response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
        respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlBlockWall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency), serviceInvoices = serviceInvoices, serviceBillingMode = serviceBillingMode, integrationSettings = integrationSettings))
        return
    }

    when (result.type) {
        "frontend" -> {
            if (paywall != null) {
                val (serviceBillingMode, serviceInvoices) = gateService.serviceInvoices(paywall)
                response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlBlockWall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency), serviceInvoices = serviceInvoices, serviceBillingMode = serviceBillingMode, integrationSettings = integrationSettings))
            } else {
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(null, integrationSettings))
            }
        }
        else -> respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(paywall, integrationSettings))
    }
}

