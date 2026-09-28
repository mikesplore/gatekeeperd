package com.gatekeeper.feature.payment.domain

import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.InitiatedPayment
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.usecase.ApplyWebhookEvent
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentUseCasesTest {
    @Test
    fun `successful webhook applies once and validates amount`() {
        val repository = FakePaymentRepository(payment())
        val effects = FakePaymentEffects()
        val useCase = ApplyWebhookEvent(repository, effects)

        assertFalse(useCase(command(amount = BigDecimal.ZERO)))
        assertTrue(useCase(command()))
        assertFalse(useCase(command()))
        assertEquals("success", repository.payment?.status)
        assertEquals(1, effects.successCount)
    }

    @Test
    fun `reconciliation verifies pending payments via provider gateway`() {
        val repository = FakePaymentRepository(payment())
        val effects = FakePaymentEffects()
        val apply = ApplyWebhookEvent(repository, effects)
        val gateway = object : PaymentGateway {
            override val provider = "paystack"
            override suspend fun initiate(command: InitiatePaymentCommand) = Result.failure<InitiatedPayment>(UnsupportedOperationException())
            override suspend fun verify(reference: String) = Result.success(VerifiedPayment("success", BigDecimal("20.00"), "KES"))
        }
        val useCase = ReconcilePayments(repository, mapOf("paystack" to gateway), apply)

        kotlinx.coroutines.runBlocking { assertEquals(1, useCase(20)) }
        assertEquals("success", repository.payment?.status)
        assertEquals(1, effects.successCount)
    }

    @Test
    fun `payment initiation asks gateway then persists provider reference`() = kotlinx.coroutines.runBlocking {
        val projectId = UUID.randomUUID()
        val repository = FakePaymentRepository(null)
        var received: InitiatePaymentCommand? = null
        val gateway = object : PaymentGateway {
            override val provider = "mpesa"
            override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> {
                received = command
                return Result.success(InitiatedPayment("checkout-1"))
            }
            override suspend fun verify(reference: String) = Result.failure<VerifiedPayment>(UnsupportedOperationException())
        }
        val useCase = InitiatePayment(
            repository,
            object : PaymentProjectPort { override fun find(slug: String) = PaymentProject(projectId, slug, "KES", null) },
            object : PaymentBalancePort { override suspend fun availableAmount(projectId: UUID, requestedAmount: BigDecimal?) = requestedAmount ?: BigDecimal("50.00") },
            mapOf("mpesa" to gateway)
        )

        val result = useCase(InitiatePayment.Command("mpesa", "demo", BigDecimal("25"), phone = "254712345678", currency = "KES"))

        assertTrue(result.isSuccess)
        assertEquals("checkout-1", repository.payment?.providerReference)
        assertEquals(BigDecimal("25"), repository.payment?.amount)
        assertEquals("254712345678", received?.phone)
    }

    private fun command(amount: BigDecimal = BigDecimal("20.00")) = ApplyWebhookEvent.Command(
        provider = "paystack", reference = "ref-1", status = "success", verifiedVia = "test", amount = amount, currency = "KES"
    )

    private fun payment() = Payment(UUID.randomUUID(), UUID.randomUUID(), "paystack", "ref-1", BigDecimal("20.00"), "pending", currency = "KES")

    private class FakePaymentRepository(var payment: Payment?) : PaymentRepository {
        override fun findByProviderReference(provider: String, reference: String) = payment?.takeIf { it.provider == provider && it.providerReference == reference }
        override fun findById(id: UUID) = payment?.takeIf { it.id == id }
        override fun findByReference(reference: String) = payment?.takeIf { it.reference == reference }
        override fun create(projectId: UUID, provider: String, reference: String, amount: BigDecimal, status: String, rawPayload: String?) =
            (payment?.copy(projectId = projectId, provider = provider, reference = reference, providerReference = reference, amount = amount, status = status)
                ?: Payment(UUID.randomUUID(), projectId, provider, reference, amount, status)).also { payment = it }
        override fun save(payment: Payment): Payment { this.payment = payment; return payment }
        override fun updateStatus(provider: String, reference: String, status: String, verifiedVia: String, paidAt: LocalDateTime?, amount: BigDecimal?) {
            payment = payment?.copy(status = status, amount = amount ?: payment!!.amount, paidAt = paidAt ?: payment!!.paidAt)
        }
        override fun pendingPayments(olderThanMinutes: Long) = listOfNotNull(payment?.takeIf { it.status == "pending" })
    }

    private class FakePaymentEffects : PaymentEffects {
        var successCount = 0
        override fun acceptSuccessfulPayment(payment: Payment?, projectId: UUID, amount: BigDecimal, currency: String?) = currency == null || payment?.currency == currency
        override fun paymentSucceeded(payment: Payment, amount: BigDecimal, currency: String?, paidAt: LocalDateTime?) { successCount++ }
        override fun paymentFailed(payment: Payment) = Unit
        override fun paymentReversed(payment: Payment) = Unit
    }
}
