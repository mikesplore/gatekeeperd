package com.gatekeeper.gate

import com.gatekeeper.db.repositories.ProjectRepository
import java.math.BigDecimal
import java.time.LocalDate
import com.gatekeeper.payments.ProjectBalanceService

data class PaywallInfo(
    val slug: String,
    val name: String,
    val domain: String,
    val amountDue: BigDecimal?,
    val currency: String,
    val dueDate: LocalDate?,
    val customerEmail: String?
) {
    companion object {
        fun from(project: ProjectRepository.ProjectRecord) = PaywallInfo(
            slug = project.slug,
            name = project.name,
            domain = project.domain,
            amountDue = ProjectBalanceService.outstandingBalance(project),
            currency = project.currency,
            dueDate = project.dueDate,
            customerEmail = project.customerEmail
        )
    }
}
