package com.gatekeeper.scheduler

import com.gatekeeper.feature.integrations.domain.repository.IntegrationOutboxRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

object IntegrationOutboxJob {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.IntegrationOutboxJob")
    fun start(scope: CoroutineScope, outbox: IntegrationOutboxRepository, payments: PaymentRepository, balances: ProjectBalanceAdapter) { scope.launch {
        runWorkerLoop("integration-outbox", 60_000, "worker:integration-outbox", logger) {
            run {
                outbox.claim().forEach { event ->
                    if (ScribedIntegrationClient.deliver(event, payments, balances)) outbox.markDelivered(event.id)
                    else outbox.markFailed(event.id, "Scribed delivery failed", event.attempts)
                }
            }
        }
    } }
}
