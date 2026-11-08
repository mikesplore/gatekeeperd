package com.gatekeeper.feature.payment.data

import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.feature.payment.data.persistence.PaymentEffectsAdapter
import com.gatekeeper.feature.payment.domain.model.Payment
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService
import com.gatekeeper.feature.service.domain.repository.ServiceInvoiceRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentEffectsAdapterTest {
    @Test
    fun `service payment reversal blocks only its service`() {
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()
        var projectWideBlockCalls = 0
        var serviceBlock: Pair<UUID, UUID>? = null
        var auditAction: String? = null
        val project = ProjectRecord(
            id = projectId, slug = "project", name = "Project", domain = "example.test", status = "active",
            blockReason = null, deploymentMode = "managed", serviceMode = "production", lifecycleStatus = "active",
            customerName = null, customerEmail = null, customerPhone = null, billingName = null, billingEmail = null,
            billingAddress = null, paystackCustomerCode = null, amountDue = null, baseAmount = null, currency = "KES",
            dueDate = null, gracePeriodDays = 0, createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now()
        )
        val projects = proxy<ProjectRepository> { method, args ->
            when (method.name) {
                "findById" -> project
                "setStatusAndDueDate" -> { projectWideBlockCalls++; null }
                "invalidateCache" -> null
                else -> defaultValue(method.returnType)
            }
        }
        val services = proxy<ServiceRepository> { method, args ->
            if (method.name == "blockForPaymentReversal") {
                serviceBlock = args!![0] as UUID to args[1] as UUID
                true
            } else defaultValue(method.returnType)
        }
        val audit = proxy<AuditRepository> { method, args ->
            if (method.name == "write") auditAction = args!![1] as String
            defaultValue(method.returnType)
        }
        val serviceInvoices = ServiceInvoiceApplicationService(
            proxy<ServiceInvoiceRepository> { method, _ -> defaultValue(method.returnType) },
            proxy<PaymentRepository> { method, _ -> defaultValue(method.returnType) },
            proxy<ProjectAdjustmentRepository> { method, _ -> defaultValue(method.returnType) }
        )
        val adapter = PaymentEffectsAdapter(audit, projects, services, serviceInvoices)

        adapter.paymentReversed(Payment(UUID.randomUUID(), projectId, "mpesa", "receipt", BigDecimal("10"), "success", serviceId = serviceId))

        assertEquals(projectId to serviceId, serviceBlock)
        assertEquals(0, projectWideBlockCalls)
        assertEquals("service_payment_reversed", auditAction)
    }

    @Test
    fun `project-scoped payment reversal remains a project-wide block`() {
        val projectId = UUID.randomUUID()
        var projectWideBlockCalls = 0
        var serviceBlockCalls = 0
        val project = ProjectRecord(
            id = projectId, slug = "project", name = "Project", domain = "example.test", status = "active",
            blockReason = null, deploymentMode = "managed", serviceMode = "production", lifecycleStatus = "active",
            customerName = null, customerEmail = null, customerPhone = null, billingName = null, billingEmail = null,
            billingAddress = null, paystackCustomerCode = null, amountDue = null, baseAmount = null, currency = "KES",
            dueDate = null, gracePeriodDays = 0, createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now()
        )
        val projects = proxy<ProjectRepository> { method, _ ->
            when (method.name) {
                "findById" -> project
                "setStatusAndDueDate" -> { projectWideBlockCalls++; null }
                "invalidateCache" -> null
                else -> defaultValue(method.returnType)
            }
        }
        val services = proxy<ServiceRepository> { method, _ ->
            if (method.name == "blockForPaymentReversal") serviceBlockCalls++
            defaultValue(method.returnType)
        }
        val audit = proxy<AuditRepository> { method, _ -> defaultValue(method.returnType) }
        val adapter = PaymentEffectsAdapter(
            audit, projects, services,
            ServiceInvoiceApplicationService(
                proxy<ServiceInvoiceRepository> { method, _ -> defaultValue(method.returnType) },
                proxy<PaymentRepository> { method, _ -> defaultValue(method.returnType) },
                proxy<ProjectAdjustmentRepository> { method, _ -> defaultValue(method.returnType) }
            )
        )

        adapter.paymentReversed(Payment(UUID.randomUUID(), projectId, "paystack", "receipt", BigDecimal("10"), "success"))

        assertEquals(1, projectWideBlockCalls)
        assertEquals(0, serviceBlockCalls)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(noinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> handler(method, args) } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
