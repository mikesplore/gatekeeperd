package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentRepository

class GetPaymentDashboardData(private val payments: PaymentRepository) {
    operator fun invoke(): Data {
        val all = payments.findAllFiltered(null, null, null, null, 10_000, 0).first.map { it.payment }
        val (thisMonth, lastMonth) = payments.revenueTotals()
        return Data(all.groupingBy { it.status.lowercase() }.eachCount().mapValues { it.value.toLong() }, thisMonth.toPlainString(), lastMonth.toPlainString())
    }

    data class Data(val paymentCountsByStatus: Map<String, Long>, val thisMonthRevenue: String, val lastMonthRevenue: String)
}
