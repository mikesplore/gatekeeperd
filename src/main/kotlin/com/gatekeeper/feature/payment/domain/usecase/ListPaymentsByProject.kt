package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.util.UUID

class ListPaymentsByProject(private val payments: PaymentRepository) {
    operator fun invoke(projectId: UUID): List<Payment> = payments.findByProjectId(projectId)
}
