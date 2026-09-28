package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import com.gatekeeper.feature.payment.domain.model.MpesaPhoneNumber

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
        val phone = if (gateway.provider == "mpesa") {
            require(project.currency.equals("KES", ignoreCase = true)) { "M-Pesa payments are only supported in KES" }
            require(amount.stripTrailingZeros().scale() <= 0) { "M-Pesa payment amount must be a whole KES amount" }
            val suppliedPhone = command.phone?.takeIf { it.isNotBlank() } ?: error("A phone number is required for M-Pesa payments")
            MpesaPhoneNumber.normalize(suppliedPhone) ?: error("Enter a valid Kenyan mobile number, such as 0712345678 or 254712345678")
        } else command.phone
        val initiated = gateway.initiate(
            InitiatePaymentCommand(project.id, project.slug, command.email ?: project.customerEmail, phone, amount, project.currency, command.callbackUrl)
        ).getOrThrow()
        payments.create(project.id, gateway.provider, initiated.reference, amount, "pending", authorizationUrl = initiated.authorizationUrl)
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
