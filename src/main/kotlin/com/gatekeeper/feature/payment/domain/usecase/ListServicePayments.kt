package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.util.UUID

class ListServicePayments(private val payments: PaymentRepository) {
    operator fun invoke(command: Command): Page {
        val (items, total) = payments.findByServiceIdPage(command.serviceId, command.limit, command.offset)
        return Page(items, total)
    }

    data class Command(val serviceId: UUID, val limit: Int, val offset: Int)
    data class Page(val items: List<Payment>, val total: Long)
}