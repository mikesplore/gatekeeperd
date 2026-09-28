package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.model.PaymentCounts
import com.gatekeeper.feature.payment.domain.model.PaymentRevenueMonth
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal

class GetPaymentRevenue(private val payments: PaymentRepository, private val currency: PaymentCurrencyPort) {
    operator fun invoke(months: Int): Report {
        val (thisMonth, lastMonth) = payments.revenueTotals()
        return Report(thisMonth, lastMonth, currency.defaultCurrency() ?: "KES", payments.revenueByMonth(months), payments.paymentCounts())
    }

    data class Report(
        val thisMonth: BigDecimal,
        val lastMonth: BigDecimal,
        val currency: String,
        val byMonth: List<PaymentRevenueMonth>,
        val counts: PaymentCounts
    )
}

fun interface PaymentCurrencyPort { fun defaultCurrency(): String? }
