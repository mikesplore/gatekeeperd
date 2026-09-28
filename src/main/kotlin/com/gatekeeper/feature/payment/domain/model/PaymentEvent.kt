package com.gatekeeper.feature.payment.domain.model

import java.time.LocalDateTime
import java.util.UUID

data class PaymentEvent(
    val id: UUID,
    val provider: String,
    val reference: String,
    val eventType: String,
    val receivedAt: LocalDateTime
)
