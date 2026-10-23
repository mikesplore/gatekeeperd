package com.gatekeeper.gate

import com.gatekeeper.api.InputValidators
import com.gatekeeper.api.respondError
import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.LocalDate

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

private suspend fun ApplicationCall.respondBlocked(serviceRepository: ServiceRepository,result: GateResult.Blocked, scribed: ScribedIntegrationClient) {
    val integrationSettings = application.get<AppSettings>().integrations
    val paywall = result.paywall
    if (prefersHtml() && paywall != null) {
        val (serviceBillingMode, serviceInvoices) = loadServiceInvoices(serviceRepository = serviceRepository,paywall, scribed)
        response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
        respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlBlockWall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency), serviceInvoices = serviceInvoices, serviceBillingMode = serviceBillingMode, integrationSettings = integrationSettings))
        return
    }

    when (result.type) {
        "frontend" -> {
            if (paywall != null) {
                val (serviceBillingMode, serviceInvoices) = loadServiceInvoices(serviceRepository = serviceRepository, paywall, scribed)
                response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.htmlBlockWall(paywall, payEnabled = true, paymentMethods = application.get<GetPaymentMethodAvailability>()(paywall.currency), serviceInvoices = serviceInvoices, serviceBillingMode = serviceBillingMode, integrationSettings = integrationSettings))
            } else {
                respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(null, integrationSettings))
            }
        }
        else -> respond(HttpStatusCode.PaymentRequired, PaywallTemplates.jsonBlocked(paywall, integrationSettings))
    }
}

private suspend fun loadServiceInvoices(serviceRepository: ServiceRepository, paywall: PaywallInfo, scribed: ScribedIntegrationClient): Pair<Boolean, List<PaywallTemplates.ServiceInvoiceDue>> {
    val services = serviceRepository.listByProjectId(paywall.projectId)
    if (services.size <= 1) return false to emptyList()
    var serviceBillingMode = false
    val invoices = services.mapNotNull { service ->
        val lookup = scribed.serviceInvoiceStatus(service.id.toString())
        if (lookup.status == HttpStatusCode.NotFound) return@mapNotNull null
        serviceBillingMode = true
        if (lookup.status?.value !in 200..299) return@mapNotNull null
        val invoice = lookup.body?.get("invoice")?.jsonObject ?: return@mapNotNull null
        val amountDue = invoice["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return@mapNotNull null
        if (amountDue <= BigDecimal.ZERO) return@mapNotNull null
        val currency = invoice["currency"]?.jsonPrimitive?.contentOrNull ?: paywall.currency
        val dueDate = invoice["due_date"]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        PaywallTemplates.ServiceInvoiceDue(service.id, service.name, amountDue, currency, dueDate)
    }
    return serviceBillingMode to invoices
}

fun Application.configureGateRoutes(serviceRepository: ServiceRepository) {
    val integrationSettings = get<AppSettings>().integrations
    val scribed = get<ScribedIntegrationClient>()
    routing {
        get("/api/gate/check") {
            val slug = call.requireProjectSlug() ?: return@get
            when (val result = call.application.get<GateApplicationService>().check(slug, call.requestedDomain())) {
                is GateResult.Active -> call.respond(HttpStatusCode.OK, "")
                is GateResult.Blocked -> call.respondBlocked(serviceRepository, result, scribed)
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
            val (serviceBillingMode, serviceInvoices) = loadServiceInvoices(serviceRepository = serviceRepository,gateResult.paywall, scribed)
            call.response.header(HttpHeaders.ContentType, ContentType.Text.Html.withCharset(Charsets.UTF_8).toString())
            call.respond(
                HttpStatusCode.PaymentRequired,
                PaywallTemplates.htmlBlockWall(
                    gateResult.paywall,
                    payEnabled = true,
                    paymentMethods = call.application.get<GetPaymentMethodAvailability>()(gateResult.paywall.currency),
                    serviceInvoices = serviceInvoices,
                    serviceBillingMode = serviceBillingMode,
                    integrationSettings = integrationSettings
                )
            )
        }

    }
}
