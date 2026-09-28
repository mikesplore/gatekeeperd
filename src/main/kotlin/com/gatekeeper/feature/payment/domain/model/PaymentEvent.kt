package com.gatekeeper.feature.payment.domain.model

import java.time.LocalDateTime
import java.util.UUID

data class PaymentEvent(
    val id: UUID,
    val provider: String,
    val reference: String?,
    val eventType: String,
    val receivedAt: LocalDateTime,
    val dedupeKey: String? = null,
    val processingStatus: String = "received",
    val processingAttempts: Int = 0,
    val processingError: String? = null,
    val processedAt: LocalDateTime? = null,
    val serviceId: UUID? = null
)

data class PaymentEventReplay(val id: UUID, val rawPayload: String, val processingStatus: String)
