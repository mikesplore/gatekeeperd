package com.gatekeeper.gate

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.tables.ProjectStatus
import com.gatekeeper.db.tables.ProjectType
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.docker.GateResult
import com.gatekeeper.plugins.RedisService
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.gate.GateService")

object GateService {

    private const val REDIS_KEY_PREFIX = "project:status:"
    private const val REDIS_TTL_SECONDS = 60

    /**
     * Check whether a project identified by [slug] is allowed through the gate.
     *
     * 1. Try Redis cache first.
     * 2. On miss, query Postgres.
     * 3. If both are unreachable, apply FAIL_MODE.
     */
    fun check(slug: String): GateResult {
        // Step 1: Try Redis
        try {
            val cached = RedisService.get("$REDIS_KEY_PREFIX$slug")
            if (cached != null) {
                return when (cached) {
                    "active" -> GateResult.Active
                    "blocked", "manual_block" -> {
                        // We need the project type for the response — fetch from DB
                        val project = fetchProjectFromDb(slug)
                        if (project != null) {
                            GateResult.Blocked(
                                type = project.type.value,
                                paymentLink = null, // will be generated in Phase 4
                                projectName = project.name
                            )
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

        // Step 2: Try Postgres
        try {
            val project = fetchProjectFromDb(slug)
            if (project == null) {
                logger.warn("Gate check for unknown slug: $slug")
                return GateResult.Unknown("unknown project")
            }

            // Cache the result in Redis
            val redisValue = when (project.status) {
                ProjectStatus.ACTIVE -> "active"
                ProjectStatus.BLOCKED, ProjectStatus.MANUAL_BLOCK -> "blocked"
            }
            try {
                RedisService.set("$REDIS_KEY_PREFIX$slug", redisValue, REDIS_TTL_SECONDS)
            } catch (e: Exception) {
                logger.warn("Failed to write to Redis cache (non-fatal): ${e.message}")
            }

            return when (project.status) {
                ProjectStatus.ACTIVE -> GateResult.Active
                ProjectStatus.BLOCKED, ProjectStatus.MANUAL_BLOCK -> GateResult.Blocked(
                    type = project.type.value,
                    paymentLink = null,
                    projectName = project.name
                )
            }
        } catch (e: Exception) {
            logger.error("Postgres unavailable for gate check (slug=$slug): ${e.message}")
        }

        // Step 3: Both Redis and Postgres unreachable — apply FAIL_MODE
        logger.warn("Both Redis and Postgres unreachable for slug=$slug, applying FAIL_MODE=${AppConfig.failMode}")
        return when (AppConfig.failMode.lowercase()) {
            "open" -> {
                logger.error("CRITICAL: FAIL_MODE=open — allowing traffic for slug=$slug despite DB outage")
                GateResult.Active
            }
            "closed" -> {
                logger.error("CRITICAL: FAIL_MODE=closed — blocking traffic for slug=$slug due to DB outage")
                GateResult.Blocked(type = "backend", projectName = slug)
            }
            else -> {
                logger.error("CRITICAL: Unknown FAIL_MODE=${AppConfig.failMode}, defaulting to closed")
                GateResult.Blocked(type = "backend", projectName = slug)
            }
        }
    }

    private fun fetchProjectFromDb(slug: String): ProjectRow? {
        return transaction {
            Projects.selectAll().where { Projects.slug eq slug }
                .singleOrNull()
                ?.let { row ->
                    ProjectRow(
                        name = row[Projects.name],
                        type = row[Projects.type],
                        status = row[Projects.status]
                    )
                }
        }
    }

    private data class ProjectRow(
        val name: String,
        val type: ProjectType,
        val status: ProjectStatus
    )
}