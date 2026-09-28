package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.model.MpesaPhoneNumber
import java.math.BigDecimal
import java.util.UUID

class InitiatePayment(
    private val payments: PaymentRepository,
    private val projects: PaymentProjectPort,
    private val balance: PaymentBalancePort,
    private val gateways: Map<String, PaymentGateway>
) {
    suspend operator fun invoke(command: Command): Result<PaymentInitiation> = runCatching {
        if (command.projectSlug.isBlank()) invalid("missing_payment_details", "project is required")
        val project = projects.find(command.projectSlug) ?: fail("project_not_found", "Project not found", FailureKind.NOT_FOUND)
        if (command.requireSuspendedProject && project.status?.lowercase() !in setOf("blocked", "manual_block")) {
            invalid("project_active", "This project is not suspended")
        }
        if (command.currency.isNotBlank() && !project.currency.equals(command.currency, ignoreCase = true)) invalid("invalid_payment_amount", "Unsupported payment currency")
        val gateway = gateways[command.provider.lowercase()]
            ?: fail("payment_unavailable", "Payment provider is unavailable", FailureKind.PROVIDER_UNAVAILABLE)
        val amount = try {
            balance.availableAmount(project.id, command.requestedAmount)
        } catch (error: IllegalArgumentException) {
            invalid("invalid_payment_amount", error.message ?: "Invalid payment amount")
        } catch (error: IllegalStateException) {
            invalid("invalid_payment_amount", error.message ?: "Invalid payment amount")
        }
        if (amount <= BigDecimal.ZERO) invalid("invalid_payment_amount", "Payment amount must be greater than zero")
        if (amount.scale().coerceAtLeast(0) > 2) invalid("invalid_payment_amount", "Payment amount cannot have more than two decimal places")
        val phone = if (gateway.provider == "mpesa") {
            if (!project.currency.equals("KES", ignoreCase = true)) invalid("invalid_payment_amount", "M-Pesa payments are only supported in KES")
            if (amount.stripTrailingZeros().scale() > 0) invalid("invalid_payment_amount", "M-Pesa payment amount must be a whole KES amount")
            val suppliedPhone = command.phone?.takeIf { it.isNotBlank() }
                ?: invalid("missing_payment_details", "A phone number is required for M-Pesa payments")
            MpesaPhoneNumber.normalize(suppliedPhone)
                ?: invalid("invalid_mpesa_phone", "Enter a valid Kenyan mobile number, such as 0712345678 or 254712345678")
        } else command.phone
        val initiated = gateway.initiate(
            InitiatePaymentCommand(project.id, project.slug, command.email ?: project.customerEmail, phone, amount, project.currency, command.callbackUrl)
        ).getOrElse { error ->
            fail(if (gateway.provider == "mpesa") "mpesa_unavailable" else "paystack_error", error.message ?: "Payment provider could not initiate payment", FailureKind.PROVIDER_UNAVAILABLE)
        }
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
        val callbackUrl: String? = null,
        val requireSuspendedProject: Boolean = false
    )

    private fun invalid(code: String, message: String): Nothing =
        fail(code, message, FailureKind.INVALID_REQUEST)

    private fun fail(code: String, message: String, kind: FailureKind): Nothing =
        throw PaymentInitiationFailure(code, message, kind)

    enum class FailureKind { NOT_FOUND, INVALID_REQUEST, PROVIDER_UNAVAILABLE }

    class PaymentInitiationFailure(
        val code: String,
        message: String,
        val kind: FailureKind
    ) : RuntimeException(message)
}

data class PaymentInitiation(val reference: String, val authorizationUrl: String?, val amount: BigDecimal)

data class PaymentProject(
    val id: UUID,
    val slug: String,
    val currency: String,
    val customerEmail: String?,
    val domain: String? = null,
    val status: String? = null
)

interface PaymentProjectPort { fun find(slug: String): PaymentProject? }
interface PaymentBalancePort { suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?): BigDecimal }
