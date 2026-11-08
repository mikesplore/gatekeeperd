package com.gatekeeper.gate

import com.gatekeeper.config.AppSettings
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.payment.domain.model.ProjectFinancials
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.project.domain.repository.GateTarget
import com.gatekeeper.feature.project.domain.repository.ProjectQueryRepository
import com.gatekeeper.plugins.Metrics
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.gate.GateService")

class GateService(
    private val projects: ProjectQueryRepository,
    private val latestPaymentLink: (java.util.UUID) -> String?,
    private val projectFinancials: (ProjectRecord) -> ProjectFinancials,
    private val applicationSettings: AppSettings.ApplicationSettings
) {

    private fun blockedResult(target: GateTarget): GateResult.Blocked {
        val project = target.project
        val projectBlocked = project.status != "active"
        val rawReason = if (projectBlocked) project.blockReason else target.serviceBlockReason
        val reasonCode = if (projectBlocked) {
            project.blockReasonCode ?: AccessBlockReason.fromLegacy(rawReason, project.status) ?: if (project.status == "blocked") AccessBlockReason.PAYMENT else AccessBlockReason.MANUAL_HOLD
        } else {
            target.serviceBlockReasonCode ?: AccessBlockReason.fromLegacy(rawReason, target.serviceAccessStatus) ?: AccessBlockReason.MANUAL_HOLD
        }
        val reasonNote = if (projectBlocked) project.blockReasonNote else target.serviceBlockReasonNote
        val resolvedNote = reasonNote?.takeIf(String::isNotBlank)
            ?: AccessBlockReason.legacyNote(rawReason)
        val source = if (projectBlocked) "project" else "service"
        val wall = PaywallInfo.from(
            project = project,
            financials = projectFinancials(project),
            domain = target.siteDomain,
            serviceId = target.serviceId,
            serviceName = target.serviceName,
            blockReasonCode = reasonCode,
            blockReasonNote = resolvedNote,
            reasonSource = source
        )
        return GateResult.Blocked(
            type = target.serviceType,
            paymentLink = latestPaymentLink(project.id),
            projectName = project.name,
            paywall = wall,
            blockReason = resolvedNote
        )
    }

    fun check(slug: String, domain: String? = null): GateResult {
        Metrics.increment("gate.check")
        val resolvedTarget = try {
            if (domain.isNullOrBlank()) projects.findGateTargetBySlug(slug)
            else projects.findGateTargetByDomain(domain)
        } catch (e: Exception) {
            Metrics.increment("gate.datastore_failure")
            logger.error("Postgres unavailable for gate check slug={}", slug, e)
            return datastoreFailureResult(slug)
        }

        if (resolvedTarget == null) {
            logger.warn("Gate check for unknown project/site: slug={} domain={}", slug, domain)
            return GateResult.Unknown("unknown project or site")
        }

        // Project billing status applies to every service. A service access block is scoped
        // to the resolved site's service and never derives from deployment health.
        val blocked = resolvedTarget.project.status != "active" || resolvedTarget.serviceAccessStatus != "active"
        return if (!blocked) GateResult.Active else try {
            blockedResult(resolvedTarget)
        } catch (e: Exception) {
            // Failure to construct payment details must never turn a known block into an allow.
            logger.error("Failed to build blocked gate response for slug={}", slug, e)
            GateResult.Blocked(type = resolvedTarget.serviceType, paymentLink = null, projectName = resolvedTarget.project.name, paywall = null)
        }
    }

    private fun datastoreFailureResult(slug: String): GateResult {
        Metrics.increment("gate.fail_mode.${applicationSettings.failMode.name.lowercase()}")
        logger.warn("Gate datastore lookup failed; applying fail mode={} slug={}", applicationSettings.failMode, slug)
        return when (applicationSettings.failMode) {
            AppSettings.FailMode.OPEN -> {
                Metrics.increment("gate.fail_open")
                logger.error("CRITICAL: FAIL_MODE=open; allowing traffic despite datastore outage slug={}", slug)
                GateResult.Active
            }
            AppSettings.FailMode.CLOSED -> {
                Metrics.increment("gate.fail_closed")
                logger.error("CRITICAL: FAIL_MODE=closed; blocking traffic due to datastore outage slug={}", slug)
                GateResult.Blocked(type = "backend", paymentLink = null, projectName = slug, paywall = null)
            }
        }
    }
}
