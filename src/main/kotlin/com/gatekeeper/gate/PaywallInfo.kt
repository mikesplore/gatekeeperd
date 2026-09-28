package com.gatekeeper.gate

import com.gatekeeper.db.repositories.ProjectRepository
import java.math.BigDecimal
import java.time.LocalDate
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.db.tables.AccessBlockReason

data class PaywallInfo(
    val slug: String,
    val name: String,
    val domain: String,
    val amountDue: BigDecimal?,
    val currency: String,
    val dueDate: LocalDate?,
    val customerEmail: String?,
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
            serviceName: String? = null,
            blockReasonCode: AccessBlockReason = AccessBlockReason.PAYMENT,
            blockReasonNote: String? = null,
            reasonSource: String = "project"
        ) = PaywallInfo(
            slug = project.slug,
            name = project.name,
            domain = domain,
            amountDue = financials.outstanding,
            currency = project.currency,
            dueDate = project.dueDate,
            customerEmail = project.customerEmail,
            serviceName = serviceName,
            blockReasonCode = blockReasonCode,
            blockReasonNote = blockReasonNote,
            reasonSource = reasonSource
        )
    }
}
