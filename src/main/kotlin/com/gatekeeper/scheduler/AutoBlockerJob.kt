package com.gatekeeper.scheduler

import com.gatekeeper.config.AppSettings
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.math.BigDecimal
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository

class AutoBlockerJob(
    private val projectRepository: ProjectRepository,
    private val serviceRepository: ServiceRepository,
    private val outboxRepository: IntegrationOutboxRepository,
    private val scribedClient: ScribedIntegrationClient
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
                        scribedClient.notifySuspension(project.copy(status = "blocked", blockReason = "overdue"), "payment_overdue", outboxRepository)
                        scribedClient.notifyInvoiceDue(project, outboxRepository)
                        logger.warn("Auto-blocked project: ${project.slug} (due: ${project.dueDate}, grace: ${project.gracePeriodDays} days)")
                    }

                    val asOf = LocalDate.now()
                    val activeProjects = projectRepository.findAll()
                        .filter { it.status == "active" }
                        .associateBy { it.id }

                    serviceRepository.listAll().forEach { service ->
                        if (service.accessStatus != "active") return@forEach
                        val project = activeProjects[service.projectId] ?: return@forEach
                        val lookup = scribedClient.serviceInvoiceStatus(service.id.toString())
                        if (lookup.status == HttpStatusCode.NotFound) return@forEach
                        if (lookup.status == null) {
                            logger.debug("Service invoice lookup unavailable: service={}, error={}", service.id, lookup.error)
                            return@forEach
                        }
                        if (lookup.status.value !in 200..299) {
                            logger.warn("Could not check service invoice: service={}, status={}, error={}", service.id, lookup.status, lookup.error)
                            return@forEach
                        }
                        val invoice = lookup.body?.get("invoice")?.jsonObject ?: return@forEach
                        val dueDate = invoice["due_date"]?.jsonPrimitive?.contentOrNull
                            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
                        val balance = invoice["balance"]?.jsonPrimitive?.contentOrNull
                            ?.toBigDecimalOrNull() ?: BigDecimal.ZERO

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