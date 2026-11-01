package com.gatekeeper.gate

import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.project.domain.repository.ProjectQueryRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ServiceInvoiceDue(
    val serviceId: UUID,
    val serviceName: String,
    val amountDue: BigDecimal,
    val currency: String,
    val dueDate: LocalDate?
)

class GateApplicationService(
    private val projects: ProjectQueryRepository,
    private val gate: GateService,
    private val services: ServiceRepository,
    private val scribed: ScribedIntegrationClient
) {
    fun check(slug: String, domain: String? = null): GateResult = gate.check(slug, domain)
    fun findProject(slug: String) = projects.findGateTargetBySlug(slug)?.project

    suspend fun serviceInvoices(paywall: PaywallInfo): Pair<Boolean, List<ServiceInvoiceDue>> {
        val projectServices = services.listByProjectId(paywall.projectId)
        if (projectServices.size <= 1) return false to emptyList()
        var serviceBillingMode = false
        val invoices = projectServices.mapNotNull { service ->
            val lookup = scribed.serviceInvoiceStatus(service.id.toString())
            if (lookup.status == HttpStatusCode.NotFound) return@mapNotNull null
            serviceBillingMode = true
            if (lookup.status?.value !in 200..299) return@mapNotNull null
            val invoice = lookup.body?.get("invoice")?.jsonObject ?: return@mapNotNull null
            val amountDue = invoice["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                ?: return@mapNotNull null
            if (amountDue <= BigDecimal.ZERO) return@mapNotNull null
            val currency = invoice["currency"]?.jsonPrimitive?.contentOrNull ?: paywall.currency
            val dueDate = invoice["due_date"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ServiceInvoiceDue(service.id, service.name, amountDue, currency, dueDate)
        }
        return serviceBillingMode to invoices
    }
}
