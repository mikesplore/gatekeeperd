package com.gatekeeper.feature.payment.domain

import com.gatekeeper.feature.payment.domain.gateway.PaymentGateway
import com.gatekeeper.feature.payment.domain.gateway.InitiatePaymentCommand
import com.gatekeeper.feature.payment.domain.gateway.InitiatedPayment
import com.gatekeeper.feature.payment.domain.gateway.VerifiedPayment
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.payment.domain.model.PaymentCounts
import com.gatekeeper.feature.payment.domain.model.PaymentRevenueMonth
import com.gatekeeper.feature.payment.domain.model.PaymentWithProject
import com.gatekeeper.feature.payment.domain.model.PaymentEvent
import com.gatekeeper.feature.payment.domain.model.PaymentEventReplay
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentEventRepository
import com.gatekeeper.feature.payment.domain.usecase.ApplyWebhookEvent
import com.gatekeeper.feature.payment.domain.usecase.PaymentEffects
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import com.gatekeeper.feature.payment.domain.usecase.InitiatePayment
import com.gatekeeper.feature.payment.domain.usecase.PaymentBalancePort
import com.gatekeeper.feature.payment.domain.usecase.PaymentProject
import com.gatekeeper.feature.payment.domain.usecase.PaymentProjectPort
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalDate
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

    @Test
    fun `provider event is applied once and recorded as processed`() {
        val repository = FakePaymentRepository(payment())
        val events = FakePaymentEventRepository()
        val useCase = ProcessPaymentEvent(
            repository, events,
            object : PaymentProjectPort { override fun find(slug: String) = PaymentProject(repository.payment!!.projectId, slug, "KES", null) },
            ApplyWebhookEvent(repository, FakePaymentEffects())
        )
        val command = ProcessPaymentEvent.Command(
            provider = "paystack", eventType = "charge.success", dedupeKey = "charge.success:ref-1",
            rawPayload = "{}", reference = "ref-1", status = "success", amount = BigDecimal("20.00"), currency = "KES"
        )

        assertEquals(ProcessPaymentEvent.Outcome.PROCESSED, useCase(command))
        assertEquals(ProcessPaymentEvent.Outcome.DUPLICATE, useCase(command))
        assertEquals("success", repository.payment?.status)
        assertEquals("processed", events.status)
    }

    private fun command(amount: BigDecimal = BigDecimal("20.00")) = ApplyWebhookEvent.Command(
        provider = "paystack", reference = "ref-1", status = "success", verifiedVia = "test", amount = amount, currency = "KES"
    )

    private fun payment() = Payment(UUID.randomUUID(), UUID.randomUUID(), "paystack", "ref-1", BigDecimal("20.00"), "pending", currency = "KES")

    private class FakePaymentRepository(var payment: Payment?) : PaymentRepository {
        override fun findByProviderReference(provider: String, reference: String) = payment?.takeIf { it.provider == provider && it.providerReference == reference }
        override fun findById(id: UUID) = payment?.takeIf { it.id == id }
        override fun findByReference(reference: String) = payment?.takeIf { it.reference == reference }
        override fun create(projectId: UUID, provider: String, reference: String, amount: BigDecimal, status: String, rawPayload: String?, authorizationUrl: String?) =
            (payment?.copy(projectId = projectId, provider = provider, reference = reference, providerReference = reference, amount = amount, status = status)
                ?: Payment(UUID.randomUUID(), projectId, provider, reference, amount, status)).also { payment = it }
        override fun save(payment: Payment): Payment { this.payment = payment; return payment }
        override fun updateStatus(provider: String, reference: String, status: String, verifiedVia: String, paidAt: LocalDateTime?, amount: BigDecimal?) {
            payment = payment?.copy(status = status, amount = amount ?: payment!!.amount, paidAt = paidAt ?: payment!!.paidAt)
        }
        override fun pendingPayments(olderThanMinutes: Long) = listOfNotNull(payment?.takeIf { it.status == "pending" })
        override fun findByProjectId(projectId: UUID) = listOfNotNull(payment?.takeIf { it.projectId == projectId })
        override fun findByProjectIdPage(projectId: UUID, limit: Int, offset: Int) = findByProjectId(projectId).drop(offset).take(limit) to findByProjectId(projectId).size.toLong()
        override fun findAllFiltered(status: String?, projectSlug: String?, from: LocalDate?, until: LocalDate?, limit: Int, offset: Int): Pair<List<PaymentWithProject>, Long> = emptyList<PaymentWithProject>() to 0L
        override fun revenueTotals() = BigDecimal.ZERO to BigDecimal.ZERO
        override fun revenueByMonth(months: Int): List<PaymentRevenueMonth> = emptyList()
        override fun paymentCounts() = PaymentCounts(0, 0, 0, 0)
        override fun latestPendingAuthorizationUrl(projectId: UUID): String? = null
    }

    private class FakePaymentEffects : PaymentEffects {
        var successCount = 0
        override fun acceptSuccessfulPayment(payment: Payment?, projectId: UUID, amount: BigDecimal, currency: String?) = currency == null || payment?.currency == currency
        override fun paymentSucceeded(payment: Payment, amount: BigDecimal, currency: String?, paidAt: LocalDateTime?, actor: String) { successCount++ }
        override fun paymentFailed(payment: Payment) = Unit
        override fun paymentReversed(payment: Payment) = Unit
    }

    private class FakePaymentEventRepository : PaymentEventRepository {
        private val dedupeKeys = mutableSetOf<String>()
        var status: String? = null
        override fun alreadyRecorded(dedupeKey: String) = dedupeKey in dedupeKeys
        override fun recordIfNew(dedupeKey: String, eventType: String, rawPayload: String, projectId: UUID?, paymentId: UUID?, reference: String?, provider: String): UUID? {
            if (!dedupeKeys.add(dedupeKey)) return null
            return UUID.randomUUID().also { status = "received" }
        }
        override fun markProcessed(id: UUID) { status = "processed" }
        override fun markFailed(id: UUID, error: String) { status = "failed" }
        override fun findByStatus(status: String?, limit: Int, offset: Int, provider: String?) = emptyList<PaymentEvent>() to 0L
        override fun findForReplay(id: UUID): PaymentEventReplay? = null
    }
}
