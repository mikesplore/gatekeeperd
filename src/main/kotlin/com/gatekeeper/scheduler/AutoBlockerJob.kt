package com.gatekeeper.scheduler

import com.gatekeeper.config.AppSettings
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.application.ServiceInvoiceApplicationService

class AutoBlockerJob(
    private val projectRepository: ProjectRepository,
    private val serviceRepository: ServiceRepository,
    private val serviceInvoices: ServiceInvoiceApplicationService
) {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.AutoBlockerJob")

    fun start(scope: CoroutineScope, settings: AppSettings.SchedulerSettings) {
        scope.launch {
            delay(10.seconds)
            runWorkerLoop("auto-blocker", settings.autoBlockerIntervalMinutes.coerceAtLeast(1) * 60_000, "worker:auto-blocker", logger) {
                runCatching {
                    val overdue = projectRepository.findPastDue(LocalDate.now())
                    overdue.forEach { project ->
                        projectRepository.updateStatus(project.id, "blocked", actor = "system", reason = "auto-block: payment overdue", blockReason = "overdue")
                        logger.warn("Auto-blocked project: ${project.slug} (due: ${project.dueDate}, grace: ${project.gracePeriodDays} days)")
                    }

                    val asOf = LocalDate.now()
                    val activeProjects = projectRepository.findAll()
                        .filter { it.status == "active" }
                        .associateBy { it.id }

                    serviceRepository.listAll().forEach { service ->
                        if (service.accessStatus != "active") return@forEach
                        val project = activeProjects[service.projectId] ?: return@forEach
                        val invoice = serviceInvoices.find(service.id) ?: return@forEach
                        val dueDate = invoice.dueDate ?: return@forEach
                        val balance = serviceInvoices.balance(service.id) ?: BigDecimal.ZERO

                        if (balance <= BigDecimal.ZERO || !dueDate.plusDays(project.gracePeriodDays.toLong()).isBefore(asOf)) return@forEach
                        if (serviceRepository.autoBlockForPayment(project.id, service.id)) {
                            logger.warn("Auto-blocked service: project={}, service={} ({}), due={}", project.slug, service.name, service.id, dueDate)
                        }
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
