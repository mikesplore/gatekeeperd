package com.gatekeeper.gate

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.gate.PaywallInfo
import com.gatekeeper.gate.GateResult
import com.gatekeeper.plugins.RedisService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.gate.GateService")

object GateService {

    private const val REDIS_KEY_PREFIX = "project:status:"
    private const val REDIS_TTL_SECONDS = 60

    private fun blockedResult(project: ProjectRepository.ProjectRecord): GateResult.Blocked =
        GateResult.Blocked(
            type = project.type,
            paymentLink = PaymentRepository.findLatestPendingAuthorizationUrl(project.id),
            projectName = project.name,
            paywall = PaywallInfo.from(project)
        )

    fun check(slug: String): GateResult {
        try {
            val cached = RedisService.get("$REDIS_KEY_PREFIX$slug")
            if (cached != null) {
                return when (cached) {
                    "active" -> GateResult.Active
                    "blocked", "manual_block" -> {
                        val project = ProjectRepository.findBySlug(slug)
                        if (project != null) {
                            blockedResult(project)
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
            val project = ProjectRepository.findBySlug(slug)
            if (project == null) {
                logger.warn("Gate check for unknown slug: $slug")
                return GateResult.Unknown("unknown project")
            }

            val redisValue = when (project.status) {
                "active" -> "active"
                "blocked", "manual_block" -> "blocked"
                else -> "blocked"
            }
            try {
                RedisService.set("$REDIS_KEY_PREFIX$slug", redisValue, REDIS_TTL_SECONDS)
            } catch (e: Exception) {
                logger.warn("Failed to write to Redis cache (non-fatal): ${e.message}")
            }

            return when (project.status) {
                "active" -> GateResult.Active
                "blocked", "manual_block" -> blockedResult(project)
                else -> blockedResult(project)
            }
        } catch (e: Exception) {
            logger.error("Postgres unavailable for gate check (slug=$slug): ${e.message}")
        }

        logger.warn("Both Redis and Postgres unreachable for slug=$slug, applying FAIL_MODE=${AppConfig.failMode}")
        return when (AppConfig.failMode.lowercase()) {
            "open" -> {
                logger.error("CRITICAL: FAIL_MODE=open — allowing traffic for slug=$slug despite DB outage")
                GateResult.Active
            }
            "closed" -> {
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