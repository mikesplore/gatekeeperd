package com.gatekeeper.scheduler

import com.gatekeeper.plugins.DistributedLock
import com.gatekeeper.plugins.Metrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import kotlin.math.min

internal suspend fun runWorkerLoop(
    name: String,
    intervalMillis: Long,
    lockKey: String,
    logger: Logger,
    work: suspend () -> Unit
) {
    var failures = 0
    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
        val started = System.nanoTime()
        try {
            DistributedLock.withLock(lockKey) { kotlinx.coroutines.runBlocking { work() } }
            Metrics.increment("worker.$name.success")
            failures = 0
        } catch (error: Throwable) {
            failures++
            Metrics.increment("worker.$name.failure")
            logger.error("$name worker run failed", error)
        } finally {
            Metrics.increment("worker.$name.runs")
            logger.debug("{} worker run completed in {}ms", name, (System.nanoTime() - started) / 1_000_000)
        }
        val backoff = if (failures == 0) intervalMillis else min(intervalMillis, 1_000L * (1L shl min(failures, 6)))
        delay(backoff)
    }
}
