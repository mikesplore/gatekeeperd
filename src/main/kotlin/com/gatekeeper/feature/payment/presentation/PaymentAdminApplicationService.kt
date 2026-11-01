package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.feature.payment.domain.usecase.RecordCashPayment
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class PaymentAdminApplicationService(private val recordCashPayment: RecordCashPayment) {
    fun captureCashPayment(
        serviceId: UUID,
        amount: Double,
        currency: String?,
        paidAt: String?,
        receiptNumber: String?,
        notes: String?,
        actor: String
    ) = runCatching {
        require(amount.isFinite() && amount > 0.0) { "Payment amount must be greater than zero" }
        val paidAtValue = paidAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        recordCashPayment(
            RecordCashPayment.Command(
                serviceId, BigDecimal.valueOf(amount), currency, paidAtValue, receiptNumber, notes, actor
            )
        ).getOrThrow()
    }
}
