package com.gatekeeper.core.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

fun interface WorkerDelay {
    suspend fun delay(millis: Long)
}

data class WorkerHealth(
    val name: String,
    val running: Boolean = false,
    val lastTickAt: Instant? = null,
    val lastFailureAt: Instant? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null
)

class WorkerSupervisor(
    private val now: () -> Instant = Instant::now,
    private val maxBackoffMillis: Long = 60_000L
) {
    private val logger = LoggerFactory.getLogger(WorkerSupervisor::class.java)
    private val health = ConcurrentHashMap<String, WorkerHealth>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun start(scope: CoroutineScope, workers: Collection<BackgroundWorker>) {
        workers.forEach { worker ->
            check(jobs[worker.name] == null) { "Worker already started: ${worker.name}" }
            health[worker.name] = WorkerHealth(worker.name, running = true)
            jobs[worker.name] = scope.launch {
                runWorker(worker)
            }
        }
    }

    fun health(): List<WorkerHealth> = health.values.sortedBy { it.name }

    suspend fun stop() {
        jobs.values.forEach { it.cancel() }
        jobs.values.joinAll()
        jobs.clear()
        health.replaceAll { _, status -> status.copy(running = false) }
    }

    private suspend fun runWorker(worker: BackgroundWorker) {
        var failures = 0
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                try {
                    worker.tick()
                    failures = 0
                    update(worker.name) { it.copy(lastTickAt = now(), consecutiveFailures = 0, lastError = null) }
                    delay(worker.intervalMillis.milliseconds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failures++
                    val backoff = min(worker.intervalMillis * (1L shl min(failures - 1, 6)), maxBackoffMillis)
                    update(worker.name) {
                        it.copy(
                            lastFailureAt = now(),
                            consecutiveFailures = failures,
                            lastError = failure.message ?: failure::class.simpleName
                        )
                    }
                    logger.error("Background worker {} failed; retrying in {} ms", worker.name, backoff, failure)
                    delay(backoff.milliseconds)
                }
            }
        } finally {
            update(worker.name) { it.copy(running = false) }
        }
    }

    private fun update(name: String, transform: (WorkerHealth) -> WorkerHealth) {
        health.computeIfPresent(name) { _, current -> transform(current) }
    }
}