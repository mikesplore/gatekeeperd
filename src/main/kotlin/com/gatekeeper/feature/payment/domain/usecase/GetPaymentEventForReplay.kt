package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.PaymentEventReplay
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import java.util.UUID

class GetPaymentEventForReplay(private val events: PaymentEventRepository) {
    operator fun invoke(id: UUID): PaymentEventReplay? = events.findForReplay(id)
}
