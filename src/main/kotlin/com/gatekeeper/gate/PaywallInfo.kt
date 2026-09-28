package com.gatekeeper.gate

import com.gatekeeper.db.repositories.ProjectRepository
import java.math.BigDecimal
import java.time.LocalDate
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.db.tables.AccessBlockReason
import java.util.UUID

data class PaywallInfo(
    val projectId: UUID,
    val slug: String,
    val name: String,
    val domain: String,
    val amountDue: BigDecimal?,
    val currency: String,
    val dueDate: LocalDate?,
    val customerEmail: String?,
    val serviceId: UUID? = null,
    val serviceName: String? = null,
    val blockReasonCode: AccessBlockReason = AccessBlockReason.PAYMENT,
    val blockReasonNote: String? = null,
    val reasonSource: String = "project"
) {
    companion object {
        fun from(
            project: ProjectRepository.ProjectRecord,
            financials: ProjectFinancials,
            domain: String = project.domain,
            serviceId: UUID? = null,
            serviceName: String? = null,
            blockReasonCode: AccessBlockReason = AccessBlockReason.PAYMENT,
            blockReasonNote: String? = null,
            reasonSource: String = "project"
        ) = PaywallInfo(
            projectId = project.id,
            slug = project.slug,
            name = project.name,
            domain = domain,
            amountDue = financials.outstanding,
            currency = project.currency,
            dueDate = project.dueDate,
            customerEmail = project.customerEmail,
            serviceId = serviceId,
            serviceName = serviceName,
            blockReasonCode = blockReasonCode,
            blockReasonNote = blockReasonNote,
            reasonSource = reasonSource
        )
    }
}
