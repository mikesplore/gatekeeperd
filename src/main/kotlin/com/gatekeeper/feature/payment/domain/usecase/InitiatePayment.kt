package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

class InitiatePayment(
    private val payments: PaymentRepository,
    private val projects: PaymentProjectPort,
    private val balance: PaymentBalancePort,
    private val gateways: Map<String, PaymentGateway>
) {
    suspend operator fun invoke(command: Command): Result<PaymentInitiation> = runCatching {
        val project = projects.find(command.projectSlug) ?: error("Project not found")
        require(project.currency.equals(command.currency, ignoreCase = true)) { "Unsupported payment currency" }
        val gateway = gateways[command.provider.lowercase()] ?: error("Payment provider is unavailable")
        val amount = balance.availableAmount(project.id, command.requestedAmount)
        require(amount > BigDecimal.ZERO) { "Payment amount must be greater than zero" }
        require(amount.scale().coerceAtLeast(0) <= 2) { "Payment amount cannot have more than two decimal places" }
        val initiated = gateway.initiate(
            InitiatePaymentCommand(project.id, project.slug, command.email ?: project.customerEmail, command.phone, amount, project.currency, command.callbackUrl)
        ).getOrThrow()
        payments.create(project.id, gateway.provider, initiated.reference, amount, "pending")
        PaymentInitiation(initiated.reference, initiated.authorizationUrl, amount)
    }

    data class Command(
        val provider: String,
        val projectSlug: String,
        val requestedAmount: BigDecimal? = null,
        val email: String? = null,
        val phone: String? = null,
        val currency: String,
        val callbackUrl: String? = null
    )
}

data class PaymentInitiation(val reference: String, val authorizationUrl: String?, val amount: BigDecimal)

data class PaymentProject(val id: UUID, val slug: String, val currency: String, val customerEmail: String?)

interface PaymentProjectPort { fun find(slug: String): PaymentProject? }
interface PaymentBalancePort { suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?): BigDecimal }
