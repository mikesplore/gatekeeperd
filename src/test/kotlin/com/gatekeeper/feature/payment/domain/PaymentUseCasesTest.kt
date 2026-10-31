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
import com.gatekeeper.feature.payment.domain.usecase.PaymentServicePort
import com.gatekeeper.feature.payment.domain.usecase.ProcessPaymentEvent
import com.gatekeeper.feature.payment.domain.usecase.HandleMpesaCallback
import com.gatekeeper.feature.payment.domain.usecase.MpesaCallbackData
import com.gatekeeper.feature.payment.domain.usecase.MpesaPaymentVerifier
import com.gatekeeper.feature.payment.data.provider.mpesaQueryStatus
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentMethodAvailability
import com.gatekeeper.feature.payment.domain.usecase.PaymentProviderReadiness
import com.gatekeeper.feature.service.domain.repository.ServiceRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

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
        assertTrue(repository.payment?.paidAt != null)
        assertEquals(1, effects.successCount)
    }

    @Test
    fun `provider event cannot settle a payment recorded under another provider`() {
        val repository = FakePaymentRepository(payment())
        val effects = FakePaymentEffects()
        val useCase = ApplyWebhookEvent(repository, effects)

        assertFalse(
            useCase(
                ApplyWebhookEvent.Command(
                    provider = "mpesa", reference = "ref-1", status = "success",
                    verifiedVia = "test", amount = BigDecimal("20.00"), currency = "KES"
                )
            )
        )
        assertEquals("pending", repository.payment?.status)
        assertEquals(0, effects.successCount)
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
        val serviceRepository = unusedServiceRepository()
        val serviceId = UUID.randomUUID()
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
            object : PaymentBalancePort { override suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?) = requestedAmount ?: BigDecimal("50.00") },
            mapOf("mpesa" to gateway), serviceRepository, PaymentServicePort { _, _, _ -> true }
        )

        val result = useCase(InitiatePayment.Command("mpesa", "demo", BigDecimal("25"), phone = "254712345678", currency = "KES", serviceId = serviceId))

        assertTrue(result.isSuccess)
        assertEquals("checkout-1", repository.payment?.providerReference)
        assertEquals(BigDecimal("25"), repository.payment?.amount)
        assertEquals("254712345678", received?.phone)
    }

    @Test
    fun `payment initiation resolves project from service when project slug is omitted`() = runBlocking {
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()
        val repository = FakePaymentRepository(null)
        var received: InitiatePaymentCommand? = null
        val gateway = object : PaymentGateway {
            override val provider = "paystack"
            override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> {
                received = command
                return Result.success(InitiatedPayment("service-checkout", "https://checkout.example"))
            }
            override suspend fun verify(reference: String) = Result.failure<VerifiedPayment>(UnsupportedOperationException())
        }
        val useCase = InitiatePayment(
            repository,
            object : PaymentProjectPort {
                override fun find(slug: String): PaymentProject? = null
                override fun findForService(serviceId: UUID) = PaymentProject(projectId, "resolved-project", "KES", null)
            },
            object : PaymentBalancePort {
                override suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?) = BigDecimal("42.00")
            },
            mapOf("paystack" to gateway), unusedServiceRepository(), PaymentServicePort { _, _, _ -> true }
        )

        val result = useCase(InitiatePayment.Command("paystack", "", currency = "KES", serviceId = serviceId))

        assertTrue(result.isSuccess)
        assertEquals("resolved-project", result.getOrThrow().projectSlug)
        assertEquals("resolved-project", received?.projectSlug)
        assertEquals(serviceId, repository.payment?.serviceId)
    }

    @Test
    fun `mpesa initiation rejects fractional KES amounts before calling provider`() = kotlinx.coroutines.runBlocking {
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()
        var gatewayCalled = false
        val gateway = object : PaymentGateway {
            override val provider = "mpesa"
            override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> {
                gatewayCalled = true
                return Result.success(InitiatedPayment("checkout-1"))
            }
            override suspend fun verify(reference: String) = Result.failure<VerifiedPayment>(UnsupportedOperationException())
        }
        val useCase = InitiatePayment(
            FakePaymentRepository(null),
            object : PaymentProjectPort { override fun find(slug: String) = PaymentProject(projectId, slug, "KES", null) },
            object : PaymentBalancePort { override suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?) = requestedAmount ?: BigDecimal("50.00") },
            mapOf("mpesa" to gateway), unusedServiceRepository(), PaymentServicePort { _, _, _ -> true }
        )

        val result = useCase(InitiatePayment.Command("mpesa", "demo", BigDecimal("25.50"), phone = "0712345678", currency = "KES", serviceId = serviceId))

        assertTrue(result.isFailure)
        assertFalse(gatewayCalled)
    }

    @Test
    fun `payment initiation serializes balance check provider call and pending record per service`() = runBlocking {
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()
        val repository = FakePaymentRepository(null)
        var gatewayCalls = 0
        val gateway = object : PaymentGateway {
            override val provider = "mpesa"
            override suspend fun initiate(command: InitiatePaymentCommand): Result<InitiatedPayment> {
                gatewayCalls++
                delay(30)
                return Result.success(InitiatedPayment("checkout-$gatewayCalls"))
            }
            override suspend fun verify(reference: String) = Result.failure<VerifiedPayment>(UnsupportedOperationException())
        }
        val useCase = InitiatePayment(
            repository,
            object : PaymentProjectPort { override fun find(slug: String) = PaymentProject(projectId, slug, "KES", null) },
            object : PaymentBalancePort {
                override suspend fun availableAmount(serviceRepository: ServiceRepository, projectId: UUID, requestedAmount: BigDecimal?) =
                    if (repository.payment?.status == "pending") error("A payment is already pending") else BigDecimal("50")
            },
            mapOf("mpesa" to gateway), unusedServiceRepository(), PaymentServicePort { _, _, _ -> true }
        )
        val command = InitiatePayment.Command("mpesa", "demo", BigDecimal("25"), phone = "254712345678", currency = "KES", serviceId = serviceId)

        val first = async(Dispatchers.Default) { useCase(command) }
        val second = async(Dispatchers.Default) { useCase(command) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, gatewayCalls)
        assertEquals("pending", repository.payment?.status)
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

    @Test
    fun `mpesa callback never applies callback result without provider verification`() = kotlinx.coroutines.runBlocking {
        val repository = FakePaymentRepository(payment().copy(provider = "mpesa", reference = "checkout-1", providerReference = "checkout-1"))
        val events = FakePaymentEventRepository()
        val effects = FakePaymentEffects()
        val process = ProcessPaymentEvent(
            repository, events,
            object : PaymentProjectPort { override fun find(slug: String) = null },
            ApplyWebhookEvent(repository, effects)
        )
        var verifiedReference: String? = null
        val useCase = HandleMpesaCallback(
            { MpesaCallbackData("checkout-1", 0) },
            process,
            MpesaPaymentVerifier { reference -> verifiedReference = reference }
        )

        assertEquals(ProcessPaymentEvent.Outcome.PROCESSED, useCase("callback"))
        assertEquals("checkout-1", verifiedReference)
        assertEquals("pending", repository.payment?.status)
        assertEquals(0, effects.successCount)
        assertEquals("processed", events.status)
    }

    @Test
    fun `payment method availability limits mpesa to KES`() {
        val useCase = GetPaymentMethodAvailability(object : PaymentProviderReadiness {
            override fun isConfigured(provider: String) = true
        })

        assertTrue(useCase("KES").mpesa)
        assertFalse(useCase("USD").mpesa)
        assertTrue(useCase("USD").paystack)
    }

    @Test
    fun `mpesa query acceptance alone does not mean payment succeeded`() {
        assertEquals("pending", mpesaQueryStatus(null))
        assertEquals("success", mpesaQueryStatus(0))
        assertEquals("abandoned", mpesaQueryStatus(1032))
        assertEquals("failed", mpesaQueryStatus(1))
    }

    private fun command(amount: BigDecimal = BigDecimal("20.00")) = ApplyWebhookEvent.Command(
        provider = "paystack", reference = "ref-1", status = "success", verifiedVia = "test", amount = amount, currency = "KES"
    )

    private fun payment() = Payment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "paystack",
        "ref-1",
        BigDecimal("20.00"),
        "pending",
        currency = "KES",
        serviceId = UUID.randomUUID()
    )

    private class FakePaymentRepository(var payment: Payment?) : PaymentRepository {
        override fun findByProviderReference(provider: String, reference: String) = payment?.takeIf { it.provider == provider && it.providerReference == reference }
        override fun findById(id: UUID) = payment?.takeIf { it.id == id }
        override fun findByReference(reference: String) = payment?.takeIf { it.reference == reference }
        override fun create(projectId: UUID, provider: String, reference: String, amount: BigDecimal, status: String, rawPayload: String?, authorizationUrl: String?, serviceId: UUID?) =
            (payment?.copy(projectId = projectId, provider = provider, reference = reference, providerReference = reference, amount = amount, status = status, serviceId = serviceId)
                ?: Payment(UUID.randomUUID(), projectId, provider, reference, amount, status, serviceId = serviceId)).also { payment = it }
        override fun save(payment: Payment): Payment { this.payment = payment; return payment }
        override fun updateStatus(provider: String, reference: String, status: String, verifiedVia: String, paidAt: LocalDateTime?, amount: BigDecimal?) {
            payment = payment?.copy(status = status, amount = amount ?: payment!!.amount, paidAt = paidAt ?: payment!!.paidAt)
        }
        override fun pendingPayments(olderThanMinutes: Long) = listOfNotNull(payment?.takeIf { it.status == "pending" })
        override fun findPendingByProjectId(projectId: UUID) =
            listOfNotNull(payment?.takeIf { it.projectId == projectId && it.status == "pending" })
        override fun successfulAmountForProject(projectId: UUID) =
            payment?.takeIf { it.projectId == projectId && it.status == "success" }?.amount ?: BigDecimal.ZERO
        override fun pendingAmountForProject(projectId: UUID) =
            payment?.takeIf { it.projectId == projectId && it.status == "pending" }?.amount ?: BigDecimal.ZERO
        override fun findByProjectId(projectId: UUID) = listOfNotNull(payment?.takeIf { it.projectId == projectId })
        override fun findByProjectIdPage(projectId: UUID, limit: Int, offset: Int) = findByProjectId(projectId).drop(offset).take(limit) to findByProjectId(projectId).size.toLong()
        override fun findByServiceIdPage(serviceId: UUID, limit: Int, offset: Int) = listOfNotNull(payment?.takeIf { it.serviceId == serviceId }).drop(offset).take(limit) to if (payment?.serviceId == serviceId) 1L else 0L
        override fun findAllFiltered(status: String?, projectSlug: String?, from: LocalDate?, until: LocalDate?, limit: Int, offset: Int): Pair<List<PaymentWithProject>, Long> = emptyList<PaymentWithProject>() to 0L
        override fun revenueTotals() = BigDecimal.ZERO to BigDecimal.ZERO
        override fun revenueByMonth(months: Int): List<PaymentRevenueMonth> = emptyList()
        override fun paymentCounts() = PaymentCounts(0, 0, 0, 0)
        override fun latestPendingAuthorizationUrl(projectId: UUID): String? = null
    }

    private fun unusedServiceRepository(): ServiceRepository = java.lang.reflect.Proxy.newProxyInstance(
        ServiceRepository::class.java.classLoader,
        arrayOf(ServiceRepository::class.java)
    ) { _, _, _ -> throw UnsupportedOperationException("Service repository is not used by this test") } as ServiceRepository

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
