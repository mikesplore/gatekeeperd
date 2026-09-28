package com.gatekeeper.scheduler

import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

object IntegrationOutboxJob {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.IntegrationOutboxJob")
    fun start(scope: CoroutineScope, payments: PaymentRepository) { scope.launch {
        runWorkerLoop("integration-outbox", 60_000, "worker:integration-outbox", logger) {
            run {
                IntegrationOutboxRepository.claim().forEach { event ->
                    if (ScribedIntegrationClient.deliver(event, payments)) IntegrationOutboxRepository.markDelivered(event.id)
                    else IntegrationOutboxRepository.markFailed(event.id, "Scribed delivery failed", event.attempts)
                }
            }
        }
    } }
}
