package com.gatekeeper.gate

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ProjectQueryRepository
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

    private fun blockedResult(project: ProjectRepository.ProjectRecord, blockReason: String? = project.blockReason): GateResult.Blocked =
        GateResult.Blocked(
            type = project.type,
            paymentLink = latestPaymentLink(project.id),
            projectName = project.name,
            paywall = PaywallInfo.from(project, projectBalances.financials(project)),
            blockReason = blockReason
        )

    fun check(slug: String): GateResult {
        Metrics.increment("gate.check")
        try {
            val cached = RedisService.get("$REDIS_KEY_PREFIX$slug")
            if (cached != null) {
                return when (cached) {
                    "active" -> GateResult.Active
                    "blocked", "manual_block" -> {
                        val target = projects.findGateTargetBySlug(slug)
                        if (target != null) {
                            blockedResult(target.project, target.project.blockReason ?: target.serviceBlockReason)
                        } else {
                            GateResult.Unknown("unknown project")
                        }
                    }
                    else -> GateResult.Unknown("unknown status: $cached")
                }
            }
        } catch (e: Exception) {
            logger.warn("Redis unavailable for gate check (slug=$slug): ${e.message}")
        }

        try {
            val target = projects.findGateTargetBySlug(slug)
            if (target == null) {
                logger.warn("Gate check for unknown slug: $slug")
                return GateResult.Unknown("unknown project")
            }
            val project = target.project

            // Project billing status applies to every service. A service access block is scoped
            // to the resolved site's service and never derives from deployment health.
            val blocked = project.status != "active" || target.serviceAccessStatus != "active"

            val redisValue = if (blocked) "blocked" else "active"
            try {
                RedisService.set("$REDIS_KEY_PREFIX$slug", redisValue, REDIS_TTL_SECONDS)
            } catch (e: Exception) {
                logger.warn("Failed to write to Redis cache (non-fatal): ${e.message}")
            }

            return if (!blocked) GateResult.Active else blockedResult(
                project,
                project.blockReason ?: target.serviceBlockReason
            )
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
