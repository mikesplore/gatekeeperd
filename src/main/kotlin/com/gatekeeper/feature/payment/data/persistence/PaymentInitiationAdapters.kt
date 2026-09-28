package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import com.gatekeeper.payments.ProjectBalanceService
import java.math.BigDecimal
import java.util.UUID

class LegacyPaymentProjectAdapter : PaymentProjectPort {
    override fun find(slug: String): PaymentProject? = ProjectRepository.findBySlug(slug)?.let {
        PaymentProject(it.id, it.slug, it.currency, it.customerEmail)
    }
}

class LegacyPaymentBalanceAdapter : PaymentBalancePort {
    override suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?): BigDecimal {
        val project = ProjectRepository.findById(projectId) ?: error("Project not found")
        return ProjectBalanceService.requireAvailableForNewPaymentWithReconciliation(project, requestedAmount)
    }
}
