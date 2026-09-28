package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.util.UUID

class ReconcilePayment(private val payments: PaymentRepository, private val reconcilePayments: ReconcilePayments) {
    suspend operator fun invoke(id: UUID): Result<Reconciliation> = runCatching {
        val payment = payments.findById(id) ?: error("Payment not found")
        val reconciled = reconcilePayments.reconcile(payment)
        Reconciliation(id, reconciled, payments.findById(id)?.status)
    }

    data class Reconciliation(val id: UUID, val reconciled: Boolean, val status: String?)
}
