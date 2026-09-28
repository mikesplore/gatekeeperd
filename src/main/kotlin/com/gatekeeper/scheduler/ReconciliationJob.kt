package com.gatekeeper.scheduler

import com.gatekeeper.config.AppConfig
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*

object ReconciliationJob {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.ReconciliationJob")

    fun start(scope: CoroutineScope, reconcilePayments: ReconcilePayments) {
        scope.launch {
            delay(1.minutes)
            runWorkerLoop("payment-reconciliation", AppConfig.reconciliationIntervalMinutes * 60_000, "worker:payment-reconciliation", logger) {
                reconcilePayments(AppConfig.reconciliationStaleMinutes)
            }
        }
    }

}
