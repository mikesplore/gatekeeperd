package com.gatekeeper.feature.service.domain.repository

import com.gatekeeper.feature.project.domain.model.ProjectRecord
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Optional integration used only to import legacy invoices and render billing documents. */
interface ServiceInvoiceIntegrationPort {
    data class LegacyInvoice(
        val id: String?,
        val description: String,
        val amount: BigDecimal,
        val paid: BigDecimal,
        val currency: String,
        val dueDate: LocalDate?,
        val paymentReferences: Set<String>
    )

    suspend fun findLegacyInvoice(serviceId: UUID): LegacyInvoice?
    suspend fun ensureDocument(project: ProjectRecord, serviceId: UUID, serviceName: String, description: String, amount: BigDecimal): Boolean
}
