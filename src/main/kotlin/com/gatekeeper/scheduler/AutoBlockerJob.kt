package com.gatekeeper.scheduler

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.config.AppConfig
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

object AutoBlockerJob {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.AutoBlockerJob")
    fun start(scope: CoroutineScope) {
        scope.launch {
            // Wait for app + DB to stabilize
            delay(10.seconds)
            while (isActive) {
                runCatching {
                    val overdue = ProjectRepository.findPastDue(LocalDate.now())
                    overdue.forEach { project ->
                        ProjectRepository.updateStatus(project.id, "blocked", actor = "system", reason = "auto-block: payment overdue")
                        logger.warn("Auto-blocked project: ${project.slug} (due: ${project.dueDate}, grace: ${project.gracePeriodDays} days)")
                    }
                }.onFailure { 
                    if (it.message?.contains("Database.connect") == true) {
                        logger.warn("AutoBlockerJob waiting for database...")
                    } else {
                        logger.error("AutoBlockerJob run failed", it)
                    }
                }
                delay(AppConfig.autoBlockerIntervalMinutes.coerceAtLeast(1).minutes)
            }
        }
    }
}
