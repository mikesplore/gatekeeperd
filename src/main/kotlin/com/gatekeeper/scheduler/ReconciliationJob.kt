package com.gatekeeper.scheduler

import com.gatekeeper.config.AppSettings
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*

object ReconciliationJob {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.ReconciliationJob")

    fun start(scope: CoroutineScope, reconcilePayments: ReconcilePayments, settings: AppSettings.SchedulerSettings) {
        scope.launch {
            delay(1.minutes)
            runWorkerLoop("mpesa-payment-callback-recovery", 60_000, "worker:mpesa-payment-callback-recovery", logger) {
                reconcilePayments.reconcilePendingMpesaBatch()
            }
        }
        scope.launch {
            delay(1.minutes)
            runWorkerLoop("payment-reconciliation", settings.reconciliationIntervalMinutes * 60_000, "worker:payment-reconciliation", logger) {
                reconcilePayments(settings.reconciliationStaleMinutes)
            }
        }
    }

}
