package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.util.UUID

class GetPayment(private val payments: PaymentRepository) {
    operator fun invoke(id: UUID): Payment? = payments.findById(id)
}
