package com.gatekeeper.gate

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectQueryRepository
import com.gatekeeper.db.repositories.GateTarget
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.payment.domain.usecase.GetLatestPaymentLink
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.plugins.RedisService
import com.gatekeeper.plugins.Metrics
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.gate.GateService")

class GateService(
    private val projects: ProjectQueryRepository,
    private val latestPaymentLink: GetLatestPaymentLink,
    private val projectBalances: ProjectBalanceAdapter
) {

    companion object {
        private const val REDIS_KEY_PREFIX = "project:status:"
        private const val REDIS_TTL_SECONDS = 60
    }

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
            financials = projectBalances.financials(project),
            domain = target.siteDomain,
            serviceId = target.serviceId,
            serviceName = target.serviceName,
            blockReasonCode = reasonCode,
            blockReasonNote = resolvedNote,
            reasonSource = source
        )
        return GateResult.Blocked(
            type = project.type,
            paymentLink = latestPaymentLink(project.id),
            projectName = project.name,
            paywall = wall,
            blockReason = resolvedNote
        )
    }

    fun check(slug: String, domain: String? = null): GateResult {
        Metrics.increment("gate.check")
        try {
            val cached = RedisService.get("$REDIS_KEY_PREFIX$slug")
            if (cached != null) {
                return when (cached) {
                    "active" -> GateResult.Active
                    "blocked", "manual_block" -> {
                        val target = if (domain.isNullOrBlank()) projects.findGateTargetBySlug(slug)
                            else projects.findGateTargetByDomain(domain)
                        target?.let(::blockedResult) ?: GateResult.Unknown("unknown project or site")
                    }
                    else -> GateResult.Unknown("unknown status: $cached")
                }
            }
        } catch (e: Exception) {
            logger.warn("Redis unavailable for gate check (slug=$slug): ${e.message}")
        }

        try {
            val resolvedTarget = (if (domain.isNullOrBlank()) projects.findGateTargetBySlug(slug)
                else projects.findGateTargetByDomain(domain))
                ?: run {
                    logger.warn("Gate check for unknown project/site: slug=$slug domain=$domain")
                    return GateResult.Unknown("unknown project or site")
                }
            val project = resolvedTarget.project

            // Project billing status applies to every service. A service access block is scoped
            // to the resolved site's service and never derives from deployment health.
            val blocked = project.status != "active" || resolvedTarget.serviceAccessStatus != "active"

            val redisValue = if (blocked) "blocked" else "active"
            try {
                RedisService.set("$REDIS_KEY_PREFIX${resolvedTarget.siteSlug}", redisValue, REDIS_TTL_SECONDS)
            } catch (e: Exception) {
                logger.warn("Failed to write to Redis cache (non-fatal): ${e.message}")
            }

            return if (!blocked) GateResult.Active else blockedResult(resolvedTarget)
        } catch (e: Exception) {
            logger.error("Postgres unavailable for gate check (slug=$slug): ${e.message}")
        }

        logger.warn("Both Redis and Postgres unreachable for slug=$slug, applying FAIL_MODE=${AppConfig.failMode}")
        return when (AppConfig.failMode.lowercase()) {
            "open" -> {
                Metrics.increment("gate.fail_open")
                logger.error("CRITICAL: FAIL_MODE=open — allowing traffic for slug=$slug despite DB outage")
                GateResult.Active
            }
            "closed" -> {
                Metrics.increment("gate.fail_closed")
                logger.error("CRITICAL: FAIL_MODE=closed — blocking traffic for slug=$slug due to DB outage")
                GateResult.Blocked(type = "backend", paymentLink = null, projectName = slug, paywall = null)
            }
            else -> {
                logger.error("CRITICAL: Unknown FAIL_MODE=${AppConfig.failMode}, defaulting to closed")
                GateResult.Blocked(type = "backend", paymentLink = null, projectName = slug, paywall = null)
            }
        }
    }
}
