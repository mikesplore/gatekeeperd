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
    paidAt = payment.paidAt?.let { com.gatekeeper.api.ApiTimestamp.format(it) },
    createdAt = payment.createdAt?.let { com.gatekeeper.api.ApiTimestamp.format(it) }.orEmpty()
)
