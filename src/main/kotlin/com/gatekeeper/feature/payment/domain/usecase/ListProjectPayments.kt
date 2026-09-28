package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository

class ListProjectPayments(private val projects: PaymentProjectPort, private val payments: PaymentRepository) {
    operator fun invoke(command: Command): Result<Page> = runCatching {
        val project = projects.find(command.projectSlug) ?: error("Project not found")
        val (items, total) = payments.findByProjectIdPage(project.id, command.limit, command.offset)
        Page(items, total)
    }

    data class Command(val projectSlug: String, val limit: Int, val offset: Int)
    data class Page(val items: List<Payment>, val total: Long)
}
