package com.gatekeeper.feature.payment.domain.model

import java.math.BigDecimal
import java.util.UUID

data class ProjectBalance(
    val projectId: UUID,
    val currency: String,
    val outstanding: BigDecimal,
    val paid: BigDecimal
) {
    companion object {
        fun calculate(projectId: UUID, currency: String, originalCharge: BigDecimal, adjustments: BigDecimal, discounts: BigDecimal, paid: BigDecimal): ProjectBalance {
            val outstanding = (originalCharge + adjustments - discounts - paid).max(BigDecimal.ZERO)
            return ProjectBalance(projectId, currency, outstanding, paid)
        }
    }
}
