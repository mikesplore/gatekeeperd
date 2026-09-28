package com.gatekeeper.scheduler

import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.ServiceRepository
import com.gatekeeper.config.AppConfig
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

                    val asOf = LocalDate.now()
                    val activeProjects = ProjectRepository.findAll()
                        .filter { it.status == "active" }
                        .associateBy { it.id }
                    ServiceRepository.listAll().forEach { service ->
                        if (service.accessStatus != "active") return@forEach
                        val project = activeProjects[service.projectId] ?: return@forEach
                        val lookup = ScribedIntegrationClient.serviceInvoiceStatus(service.id.toString())
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
                        if (ServiceRepository.autoBlockForPayment(project.id, service.id)) {
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
