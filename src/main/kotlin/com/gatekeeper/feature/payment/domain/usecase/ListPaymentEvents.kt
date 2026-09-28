package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.PaymentEvent
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository

class ListPaymentEvents(private val events: PaymentEventRepository) {
    operator fun invoke(status: String?, provider: String?, limit: Int, offset: Int): Page {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val (items, total) = events.findByStatus(status, safeLimit, safeOffset, provider)
        return Page(items, total, safeLimit, safeOffset)
    }

    data class Page(val items: List<PaymentEvent>, val total: Long, val limit: Int, val offset: Int)
}
