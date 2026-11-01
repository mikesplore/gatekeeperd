package com.gatekeeper.feature.payment.presentation

import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import com.gatekeeper.api.dto.PaymentAdminResponse

internal fun PaymentWithProject.toAdminResponse() = PaymentAdminResponse(
    id = payment.id.toString(),
    projectId = payment.projectId.toString(),
    projectName = projectName,
    projectSlug = projectSlug,
    provider = payment.provider.lowercase(),
    providerReference = payment.providerReference,
    paystackReference = payment.providerReference,
    amount = payment.amount.toDouble(),
    gatewayStatus = payment.status,
    verifiedVia = payment.verifiedVia,
    paidAt = payment.paidAt?.toString(),
    createdAt = payment.createdAt?.toString().orEmpty()
)
