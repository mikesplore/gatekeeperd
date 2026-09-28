package com.gatekeeper.feature.payment.domain.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/** Framework-independent payment model used by payment use cases. */
data class Payment(
    val id: UUID,
    val projectId: UUID,
    val provider: String,
    val reference: String,
    val amount: BigDecimal,
    val status: String,
    val providerReference: String = reference,
    val currency: String? = null,
    val rawPayload: String? = null,
    val paidAt: LocalDateTime? = null
)
