package com.gatekeeper.worker

import com.gatekeeper.core.worker.BackgroundWorker
import com.gatekeeper.core.worker.WorkerSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WorkerSupervisorTest {
    @Test
    fun `worker failure is recorded and the worker retries`() = runBlocking {
        var attempts = 0
        val supervisor = WorkerSupervisor(
            maxBackoffMillis = 10
        )
        val worker = object : BackgroundWorker {
            override val name = "test-worker"
            override val intervalMillis = 1_000L
            override suspend fun tick() {
                attempts++
                if (attempts == 1) error("first attempt failed")
            }
        }

        supervisor.start(CoroutineScope(Job()), listOf(worker))
        try {
            withTimeout(1_000.milliseconds) {
                while (true) {
                    val status = supervisor.health().single()
                    if (attempts >= 2 && status.lastFailureAt != null && status.consecutiveFailures == 0) break
                    delay(1.milliseconds)
                }
            }

            val status = supervisor.health().single()
            assertTrue(attempts >= 2)
            assertTrue(status.lastFailureAt != null)
            assertEquals(0, status.consecutiveFailures)
        } finally {
            supervisor.stop()
        }
    }
}
