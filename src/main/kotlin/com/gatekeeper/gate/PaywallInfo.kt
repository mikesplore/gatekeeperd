package com.gatekeeper.gate

import com.gatekeeper.db.repositories.ProjectRepository
import java.math.BigDecimal
import java.time.LocalDate

data class PaywallInfo(
    val slug: String,
    val name: String,
    val domain: String,
    val amountDue: BigDecimal?,
    val currency: String,
    val dueDate: LocalDate?,
    val clientEmail: String?
) {
    companion object {
        fun from(project: ProjectRepository.ProjectRecord) = PaywallInfo(
            slug = project.slug,
            name = project.name,
            domain = project.domain,
            amountDue = project.amountDue,
            currency = project.currency,
            dueDate = project.dueDate,
            clientEmail = project.clientEmail
        )
    }
}
