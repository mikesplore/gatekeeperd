package com.gatekeeper.feature.payment.domain.usecase

import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.model.MpesaPhoneNumber
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.plugins.DistributedLock
import java.math.BigDecimal
import java.util.UUID

class InitiatePayment(
    private val payments: PaymentRepository,
    private val projects: PaymentProjectPort,
    private val balance: PaymentBalancePort,
    private val gateways: Map<String, PaymentGateway>,
    private val serviceRepository: ServiceRepository,
    private val services: PaymentServicePort = PaymentServicePort {_, _, _ -> false },
    private val mpesaInitiationGuard: MpesaInitiationGuard = MpesaInitiationGuard { _, _, _ -> MpesaInitiationGuard.Decision.Allowed },
    private val paymentEligibility: PaymentEligibilityPort = PaymentEligibilityPort { _, _, _ -> true }
) {
    suspend operator fun invoke(command: Command): Result<PaymentInitiation> = runCatching {
        val serviceId = command.serviceId ?: invalid("service_required", "Choose a service to pay for")
        DistributedLock.withSuspendLock("payment-initiation:$serviceId") {
        val project = if (command.projectSlug.isBlank()) projects.findForService(serviceId)
            else projects.find(command.projectSlug)
            ?: fail("project_not_found", "Project not found", FailureKind.NOT_FOUND)
        if (project == null) fail("project_not_found", "Project not found for this service", FailureKind.NOT_FOUND)
        if (!services.belongsToProject(serviceRepository, project.id, serviceId)) {
            fail("service_not_found", "Service not found for this project", FailureKind.NOT_FOUND)
        }
        if (!paymentEligibility.mayPay(serviceRepository, project.id, serviceId)) {
            invalid("service_not_payable", "This service is unavailable for payment")
        }
        if (project.status != null && project.status != "active" && !paymentEligibility.mayPayProject(project)) {
            invalid("project_not_payable", "This project is unavailable for payment")
        }
        if (command.requireSuspendedProject && project.status?.lowercase() !in setOf("blocked", "manual_block")) {
            invalid("project_active", "This project is not suspended")
        }
        if (command.currency.isNotBlank() && !project.currency.equals(command.currency, ignoreCase = true)) invalid("invalid_payment_amount", "Unsupported payment currency")
        val gateway = gateways[command.provider.lowercase()]
            ?: fail("payment_unavailable", "Payment provider is unavailable", FailureKind.PROVIDER_UNAVAILABLE)
        if (gateway.provider == "mpesa" && command.payerFingerprint != null &&
            payments.hasPendingMpesaPayment(serviceId, command.payerFingerprint)
        ) {
            fail("payment_pending", "An M-Pesa payment for this service and phone is already pending", FailureKind.CONFLICT)
        }
        val amount = try {
            balance.availableAmount(serviceRepository = serviceRepository,project.id, command.requestedAmount, serviceId)
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
        if (gateway.provider == "mpesa" && command.payerFingerprint != null) {
            val ip = command.clientIp?.takeIf(String::isNotBlank)
                ?: invalid("missing_client_ip", "Unable to identify the payment request source")
            when (val decision = try {
                mpesaInitiationGuard.reserve(ip, serviceId, command.payerFingerprint)
            } catch (_: Exception) {
                fail("rate_limit_unavailable", "M-Pesa initiation is temporarily unavailable", FailureKind.SERVICE_UNAVAILABLE)
            }) {
                MpesaInitiationGuard.Decision.Allowed -> Unit
                is MpesaInitiationGuard.Decision.Limited -> fail(
                    "mpesa_rate_limited", "Too many M-Pesa attempts. Try again shortly.",
                    FailureKind.RATE_LIMITED, decision.retryAfterSeconds
                )
            }
        }
        val initiated = gateway.initiate(
            InitiatePaymentCommand(project.id, project.slug, command.email ?: project.customerEmail, phone, amount, project.currency, command.callbackUrl, serviceId)
        ).getOrElse { error ->
            fail(if (gateway.provider == "mpesa") "mpesa_unavailable" else "paystack_error", error.message ?: "Payment provider could not initiate payment", FailureKind.PROVIDER_UNAVAILABLE)
        }
        payments.create(
            project.id, gateway.provider, initiated.reference, amount, "pending",
            authorizationUrl = initiated.authorizationUrl, serviceId = serviceId,
            payerFingerprint = command.payerFingerprint.takeIf { gateway.provider == "mpesa" }
        )
        PaymentInitiation(initiated.reference, initiated.authorizationUrl, amount, project.slug)
        }
    }

    data class Command(
        val provider: String,
        val projectSlug: String,
        val requestedAmount: BigDecimal? = null,
        val email: String? = null,
        val phone: String? = null,
        val currency: String,
        val callbackUrl: String? = null,
        val requireSuspendedProject: Boolean = false,
        val serviceId: UUID? = null,
        val clientIp: String? = null,
        val payerFingerprint: String? = null
    )

    private fun invalid(code: String, message: String): Nothing =
        fail(code, message, FailureKind.INVALID_REQUEST)

    private fun fail(code: String, message: String, kind: FailureKind, retryAfterSeconds: Int? = null): Nothing =
        throw PaymentInitiationFailure(code, message, kind, retryAfterSeconds)

    enum class FailureKind { NOT_FOUND, INVALID_REQUEST, CONFLICT, RATE_LIMITED, SERVICE_UNAVAILABLE, PROVIDER_UNAVAILABLE }

    class PaymentInitiationFailure(
        val code: String,
        message: String,
        val kind: FailureKind,
        val retryAfterSeconds: Int? = null
    ) : RuntimeException(message)
}

data class PaymentInitiation(val reference: String, val authorizationUrl: String?, val amount: BigDecimal, val projectSlug: String = "")

data class PaymentProject(
    val id: UUID,
    val slug: String,
    val currency: String,
    val customerEmail: String?,
    val domain: String? = null,
    val status: String? = null,
    val blockReason: String? = null,
    val blockReasonCode: String? = null
)

interface PaymentProjectPort {
    fun find(slug: String): PaymentProject?
    fun findForService(serviceId: UUID): PaymentProject? = null
}
fun interface PaymentServicePort { fun belongsToProject( serviceRepository: ServiceRepository, projectId: UUID, serviceId: UUID): Boolean }
fun interface PaymentEligibilityPort {
    fun mayPay(serviceRepository: ServiceRepository, projectId: UUID, serviceId: UUID): Boolean
    fun mayPayProject(project: PaymentProject): Boolean = true
}
interface PaymentBalancePort {
    suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?): BigDecimal
    suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?, serviceId: UUID?): BigDecimal = availableAmount( serviceRepository = serviceRepository, projectId, requestedAmount)
    suspend fun requiresServiceScope(projectId: UUID): Boolean = false
}

fun interface MpesaInitiationGuard {
    fun reserve(clientIp: String, serviceId: UUID, payerFingerprint: String): Decision

    sealed interface Decision {
        data object Allowed : Decision
        data class Limited(val retryAfterSeconds: Int) : Decision
    }
}
