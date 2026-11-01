package com.gatekeeper.feature.service.data.integration

import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceIntegrationPort
import com.gatekeeper.integrations.ScribedIntegrationClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ScribedServiceInvoiceAdapter(private val scribed: ScribedIntegrationClient) : ServiceInvoiceIntegrationPort {
    override suspend fun findLegacyInvoice(serviceId: UUID): ServiceInvoiceIntegrationPort.LegacyInvoice? {
        val lookup = scribed.serviceInvoiceStatus(serviceId.toString())
        if (lookup.status != HttpStatusCode.OK) return null
        val invoice = lookup.body?.get("invoice")?.jsonObject ?: return null
        fun decimal(key: String) = runCatching {
            invoice[key]?.jsonPrimitive?.contentOrNull?.toBigDecimal()
        }.getOrNull()
        val amount = decimal("amount") ?: return null
        val dueDate = invoice["due_date"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val paymentReferences = ((invoice["payments"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { item ->
            runCatching { item.jsonObject["provider_reference"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }.toSet()
        return ServiceInvoiceIntegrationPort.LegacyInvoice(
            id = invoice["id"]?.jsonPrimitive?.contentOrNull,
            description = invoice["description"]?.jsonPrimitive?.contentOrNull ?: "Imported Scribed service invoice",
            amount = amount,
            paid = decimal("paid") ?: java.math.BigDecimal.ZERO,
            currency = invoice["currency"]?.jsonPrimitive?.contentOrNull ?: "USD",
            dueDate = dueDate,
            paymentReferences = paymentReferences
        )
    }

    override suspend fun ensureDocument(
        project: ProjectRecord,
        serviceId: UUID,
        serviceName: String,
        description: String,
        amount: java.math.BigDecimal
    ): Boolean {
        val result = scribed.createServiceInvoice(project, serviceId.toString(), serviceName, description, amount.toPlainString())
        return result.status?.isSuccess() == true || result.status == HttpStatusCode.Conflict
    }
}
