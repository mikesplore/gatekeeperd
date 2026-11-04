package com.gatekeeper.customer

import com.gatekeeper.config.AppEnvironment
import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.core.ports.HttpPortProbe
import com.gatekeeper.plugins.RedisUnavailableException
import io.ktor.http.*
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.get
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val supportDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val supportHtml = ContentType.Text.Html.withCharset(Charsets.UTF_8)

fun Application.configureCustomerSupportRoutes() {
    val settings = get<AppSettings>()
    val access = get<CustomerSupportAccessService>()
    val customers = get<CustomerRepository>()
    val projects = get<ProjectRepository>()
    val services = get<ServiceRepository>()
    val invoices = get<ServiceInvoiceApplicationService>()
    val upstreams = get<DeploymentUpstreamResolver>()
    val httpProbe = get<HttpPortProbe>()
    val secureCookie = settings.environment == AppEnvironment.PROD

    routing {
        get("/") {
            call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail))
        }
        get("/support") {
            call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail))
        }
        post("/support/access/request") {
            val email = runCatching { call.receiveParameters()["email"].orEmpty().trim().lowercase() }.getOrDefault("")
            if (!com.gatekeeper.api.InputValidators.isValidEmail(email)) {
                call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail, "Enter a valid email address."), HttpStatusCode.BadRequest)
                return@post
            }
            val challenge = try {
                access.requestCode(email)
            } catch (_: RedisUnavailableException) {
                call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail, "Secure sign-in is temporarily unavailable. Please try again shortly."), HttpStatusCode.ServiceUnavailable)
                return@post
            } catch (_: Exception) {
                environment.log.warn("Customer support sign-in code request failed")
                call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail, "We could not send a sign-in code right now. Please try again shortly."), HttpStatusCode.ServiceUnavailable)
                return@post
            }
            call.respondSupportHtml(CustomerSupportTemplates.codeEntry(challenge))
        }
        post("/support/access/verify") {
            val form = runCatching { call.receiveParameters() }.getOrNull()
            val challenge = form?.get("challenge").orEmpty()
            val code = form?.get("code").orEmpty().trim()
            val token = try {
                access.verifyCode(challenge, code)
            } catch (_: RedisUnavailableException) {
                call.respondSupportHtml(CustomerSupportTemplates.codeEntry(challenge, "Secure sign-in is temporarily unavailable. Please try again shortly."), HttpStatusCode.ServiceUnavailable)
                return@post
            } catch (_: Exception) {
                environment.log.warn("Customer support code verification failed")
                call.respondSupportHtml(CustomerSupportTemplates.codeEntry(challenge, "Secure sign-in is temporarily unavailable. Please try again shortly."), HttpStatusCode.ServiceUnavailable)
                return@post
            }
            if (token == null) {
                call.respondSupportHtml(CustomerSupportTemplates.codeEntry(challenge, "That code is incorrect or has expired. Request a new code and try again."), HttpStatusCode.BadRequest)
                return@post
            }
            call.response.cookies.append(
                Cookie(
                    name = CustomerSupportAccessService.SESSION_COOKIE,
                    value = token,
                    maxAge = CustomerSupportAccessService.SESSION_TTL_SECONDS,
                    path = "/",
                    secure = secureCookie,
                    httpOnly = true,
                    extensions = mapOf("SameSite" to "Strict")
                )
            )
            call.respondRedirect("/support/services", permanent = false)
        }
        get("/support/services") {
            val token = call.request.cookies[CustomerSupportAccessService.SESSION_COOKIE]
            val customerId = try {
                access.customerIdForSession(token)
            } catch (_: RedisUnavailableException) {
                call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail, "Secure sign-in is temporarily unavailable. Please try again shortly."), HttpStatusCode.ServiceUnavailable)
                return@get
            } catch (error: Exception) {
                environment.log.warn("Customer support session lookup failed")
                call.respondSupportHtml(CustomerSupportTemplates.landing(settings.integrations.supportContactEmail, "Secure sign-in is temporarily unavailable. Please try again shortly."), HttpStatusCode.ServiceUnavailable)
                return@get
            }
            if (customerId == null) {
                clearSupportCookie(call, secureCookie)
                call.respondRedirect("/", permanent = false)
                return@get
            }
            val customer = customers.findById(customerId)
            if (customer == null) {
                access.revokeSession(token)
                clearSupportCookie(call, secureCookie)
                call.respondRedirect("/", permanent = false)
                return@get
            }

            val serviceViews = projects.findByCustomerId(customerId).flatMap { project ->
                services.listByProjectId(project.id).map { service ->
                    val invoice = runCatching { invoices.find(service.id) }.getOrNull()
                    val invoiceBalance = invoice?.let { runCatching { invoices.balance(service.id) }.getOrNull() }
                    val dueAmount = invoiceBalance?.takeIf { it > BigDecimal.ZERO }
                    val blocked = !project.status.equals("active", ignoreCase = true) || !service.accessStatus.equals("active", ignoreCase = true)
                    val reasonCode = service.blockReasonCode ?: project.blockReasonCode
                    val availabilityStatus = if (blocked) "not_checked" else withContext(Dispatchers.IO) {
                        runCatching {
                            val target = upstreams.resolve(service.id, "production") ?: return@runCatching "unknown"
                            if (httpProbe.respondsSuccessfully(target.host, target.port, "/", 1_000)) "responding" else "issue"
                        }.getOrDefault("unknown")
                    }
                    CustomerSupportServiceView(
                        projectName = project.name,
                        serviceName = service.name,
                        domain = service.domain?.takeIf { it.isNotBlank() } ?: project.domain,
                        accessStatus = if (blocked) "paused" else "active",
                        statusReason = customerFacingReason(blocked, reasonCode, dueAmount),
                        availabilityStatus = availabilityStatus,
                        availabilityMessage = when (availabilityStatus) {
                            "responding" -> "The hosted application responded to a connection check."
                            "issue" -> "We could not reach the hosted application. Contact your hosting provider."
                            else -> if (blocked) "Not checked while hosting access is paused." else "Availability could not be checked."
                        },
                        invoiceDescription = invoice?.description,
                        amountDue = dueAmount?.let { formatAmount(it, invoice?.currency ?: project.currency) },
                        dueDate = invoice?.dueDate?.format(supportDateFormatter),
                        paymentUrl = dueAmount?.let {
                            "/api/gate/pay?project=${encode(project.slug)}&serviceId=${service.id}"
                        }
                    )
                }
            }
            val name = listOf(customer.firstName, customer.lastName).filter { it.isNotBlank() }.joinToString(" ")
            call.respondSupportHtml(CustomerSupportTemplates.services(name, serviceViews, settings.integrations.supportContactEmail))
        }
        post("/support/logout") {
            access.revokeSession(call.request.cookies[CustomerSupportAccessService.SESSION_COOKIE])
            clearSupportCookie(call, secureCookie)
            call.respondRedirect("/", permanent = false)
        }
    }
}

private fun clearSupportCookie(call: io.ktor.server.application.ApplicationCall, secure: Boolean) {
    call.response.cookies.append(
        Cookie(
            name = CustomerSupportAccessService.SESSION_COOKIE,
            value = "",
            maxAge = 0,
            path = "/",
            secure = secure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict")
        )
    )
}

private suspend fun ApplicationCall.respondSupportHtml(body: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.headers.append(HttpHeaders.CacheControl, "no-store, private")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
    respondText(body, supportHtml, status)
}

private fun customerFacingReason(blocked: Boolean, reason: AccessBlockReason?, balance: BigDecimal?): String {
    if (!blocked) return "Hosting access is active. This does not confirm that the website application itself is responding."
    return when {
        reason == AccessBlockReason.PAYMENT || balance?.let { it > BigDecimal.ZERO } == true ->
            "Payment overdue. This service has an outstanding invoice. Pay it to restore hosting access."
        reason == AccessBlockReason.MANUAL_HOLD -> "Your hosting provider has temporarily paused access. Contact them if you need more information."
        reason == AccessBlockReason.ABUSE_TOS -> "Access was restricted by your hosting provider. Contact them for assistance."
        reason == AccessBlockReason.SUSPENDED_BY_REQUEST -> "This service was suspended at the account holder’s request. Contact your hosting provider if you have questions."
        else -> "Your hosting provider has paused access. Contact them for assistance."
    }
}

private fun formatAmount(amount: BigDecimal, currency: String): String =
    "$currency ${amount.stripTrailingZeros().toPlainString()}"

private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
