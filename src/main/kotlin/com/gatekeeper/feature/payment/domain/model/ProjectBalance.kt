package com.gatekeeper.feature.payment.domain.model

import java.math.BigDecimal
import java.util.UUID

data class ProjectBalance(
    val projectId: UUID,
    val currency: String,
    val outstanding: BigDecimal,
    val paid: BigDecimal
)
