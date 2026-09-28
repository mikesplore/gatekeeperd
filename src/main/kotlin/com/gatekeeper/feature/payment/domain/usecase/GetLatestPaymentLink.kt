package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.util.UUID

class GetLatestPaymentLink(private val payments: PaymentRepository) {
    operator fun invoke(projectId: UUID): String? = payments.latestPendingAuthorizationUrl(projectId)
}
