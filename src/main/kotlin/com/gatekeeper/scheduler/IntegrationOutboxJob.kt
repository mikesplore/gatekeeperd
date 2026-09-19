package com.gatekeeper.scheduler

import com.gatekeeper.db.repositories.IntegrationOutboxRepository
import com.gatekeeper.integrations.ScribedIntegrationClient
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

object IntegrationOutboxJob {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.scheduler.IntegrationOutboxJob")
    fun start(scope: CoroutineScope) { scope.launch {
        while (isActive) {
            runCatching {
                IntegrationOutboxRepository.claim().forEach { event ->
                    if (ScribedIntegrationClient.deliver(event)) IntegrationOutboxRepository.markDelivered(event.id)
                    else IntegrationOutboxRepository.markFailed(event.id, "Scribed delivery failed", event.attempts)
                }
            }.onFailure { logger.error("Integration outbox run failed", it) }
            delay(1.minutes)
        }
    } }
}
