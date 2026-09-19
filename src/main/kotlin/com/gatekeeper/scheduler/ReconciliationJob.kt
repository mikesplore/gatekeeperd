package com.gatekeeper.scheduler

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.PaymentRepository
import com.gatekeeper.payments.PaymentReconciliationService
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*

object ReconciliationJob {

    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.ReconciliationJob")

    fun start(scope: CoroutineScope) {
        scope.launch {
            delay(1.minutes)
            while (isActive) {
                runCatching {
                    reconcilePendingPayments()
                }.onFailure {
                    logger.error("ReconciliationJob run failed", it)
                }
                delay(AppConfig.reconciliationIntervalMinutes.minutes)
            }
        }
    }

    private suspend fun reconcilePendingPayments() {
        val stale = PaymentRepository.findPendingOlderThan(AppConfig.reconciliationStaleMinutes)
        if (stale.isEmpty()) return

        logger.info("Reconciliation: checking ${stale.size} stale pending payment(s)")

        stale.forEach { payment ->
            PaymentReconciliationService.reconcile(payment)
        }
    }

}
