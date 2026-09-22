package com.gatekeeper.scheduler

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.config.AppConfig
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import com.gatekeeper.integrations.ScribedIntegrationClient

object AutoBlockerJob {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.AutoBlockerJob")
    fun start(scope: CoroutineScope) {
        scope.launch {
            delay(10.seconds)
            runWorkerLoop("auto-blocker", AppConfig.autoBlockerIntervalMinutes.coerceAtLeast(1) * 60_000, "worker:auto-blocker", logger) {
                runCatching {
                    val overdue = ProjectRepository.findPastDue(LocalDate.now())
                    overdue.forEach { project ->
                        ProjectRepository.updateStatus(project.id, "blocked", actor = "system", reason = "auto-block: payment overdue", blockReason = "overdue")
                        ScribedIntegrationClient.notifySuspension(project.copy(status = "blocked", blockReason = "overdue"), "payment_overdue")
                        ScribedIntegrationClient.notifyInvoiceDue(project)
                        logger.warn("Auto-blocked project: ${project.slug} (due: ${project.dueDate}, grace: ${project.gracePeriodDays} days)")
                    }
                }.onFailure {
                    if (it.message?.contains("Database.connect") == true) {
                        logger.warn("AutoBlockerJob waiting for database...")
                    } else {
                        logger.error("AutoBlockerJob run failed", it)
                    }
                }.getOrThrow()
            }
        }
    }
}
