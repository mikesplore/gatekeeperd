package com.gatekeeper.gate

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.project.domain.repository.GateTarget
import com.gatekeeper.feature.project.domain.repository.ProjectQueryRepository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GateServiceTest {
    @Test
    fun `datastore outage denies by default`() {
        val defaultMode = AppSettings.fromEnv(emptyMap(), loadDotenv = false).application.failMode
        val gate = service(failingProjects(), defaultMode)

        assertIs<GateResult.Blocked>(gate.check("acme"))
    }

    @Test
    fun `explicit fail open allows only when configured`() {
        val gate = service(failingProjects(), AppSettings.FailMode.OPEN)

        assertEquals(GateResult.Active, gate.check("acme"))
    }

    @Test
    fun `healthy database target is evaluated per requested service domain`() {
        val defaultService = target("default-site", serviceStatus = "active")
        val suspendedService = target("suspended-site", serviceStatus = "blocked")
        val projects = object : ProjectQueryRepository {
            override fun findBySlug(slug: String): ProjectRecord? = null
            override fun findGateTargetBySlug(slug: String): GateTarget? = defaultService
            override fun findGateTargetByDomain(domain: String): GateTarget? = when (domain) {
                "default.example" -> defaultService
                "suspended.example" -> suspendedService
                else -> null
            }
        }
        val gate = service(projects, AppSettings.FailMode.CLOSED)

        assertEquals(GateResult.Active, gate.check("project", "default.example"))
        assertIs<GateResult.Blocked>(gate.check("project", "suspended.example"))
    }

    @Test
    fun `blocked target remains denied if paywall details fail in explicit fail open mode`() {
        val projects = object : ProjectQueryRepository {
            override fun findBySlug(slug: String): ProjectRecord? = null
            override fun findGateTargetBySlug(slug: String): GateTarget = target("blocked-site", "blocked")
            override fun findGateTargetByDomain(domain: String): GateTarget? = null
        }

        assertIs<GateResult.Blocked>(service(projects, AppSettings.FailMode.OPEN, failFinancials = true).check("project"))
    }

    private fun failingProjects() = object : ProjectQueryRepository {
        override fun findBySlug(slug: String): ProjectRecord? = error("database unavailable")
        override fun findGateTargetBySlug(slug: String): GateTarget? = error("database unavailable")
        override fun findGateTargetByDomain(domain: String): GateTarget? = error("database unavailable")
    }

    private fun service(
        projects: ProjectQueryRepository,
        mode: AppSettings.FailMode,
        failFinancials: Boolean = false
    ) = GateService(
        projects = projects,
        latestPaymentLink = { null },
        projectFinancials = {
            if (failFinancials) error("financial data unavailable")
            ProjectFinancials(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
        },
        applicationSettings = AppSettings.fromEnv(mapOf("FAIL_MODE" to mode.name), loadDotenv = false).application
    )

    private fun target(siteSlug: String, serviceStatus: String) = GateTarget(
        project = ProjectRecord(
            id = UUID.randomUUID(), slug = "project", name = "Project", domain = "project.example",
            status = "active", blockReason = null, deploymentMode = "managed", serviceMode = "production",
            lifecycleStatus = "active", customerName = null, customerEmail = null, customerPhone = null,
            billingName = null, billingEmail = null, billingAddress = null, paystackCustomerCode = null,
            amountDue = null, baseAmount = null, currency = "KES", dueDate = null, gracePeriodDays = 3,
            createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now()
        ),
        serviceId = UUID.randomUUID(), siteDomain = "$siteSlug.example", siteSlug = siteSlug,
        serviceName = siteSlug, serviceType = "web", serviceAccessStatus = serviceStatus,
        serviceBlockReason = null, serviceBlockReasonCode = null, serviceBlockReasonNote = null
    )
}
