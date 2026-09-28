package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.time.LocalDate

class ListPayments(private val payments: PaymentRepository) {
    operator fun invoke(command: Command) = payments.findAllFiltered(
        command.status, command.projectSlug, command.from, command.until, command.limit, command.offset
    ).let { (items, total) -> Page(items, total) }

    data class Command(val status: String?, val projectSlug: String?, val from: LocalDate?, val until: LocalDate?, val limit: Int, val offset: Int)
    data class Page(val items: List<PaymentWithProject>, val total: Long)
}
