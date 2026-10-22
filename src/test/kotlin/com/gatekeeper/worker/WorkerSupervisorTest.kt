package com.gatekeeper.worker

import com.gatekeeper.core.worker.BackgroundWorker
import com.gatekeeper.core.worker.WorkerDelay
import com.gatekeeper.core.worker.WorkerSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class WorkerSupervisorTest {
    @Test
    fun `worker failure is recorded and the worker retries`() = runBlocking {
        var attempts = 0
        val supervisor = WorkerSupervisor(
            delay = WorkerDelay { delay(1) },
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
        withTimeout(1_000) {
            while (attempts < 2) delay(1)
        }

        val status = supervisor.health().single()
        assertTrue(attempts >= 2)
        assertTrue(status.consecutiveFailures == 0)
        supervisor.stop()
    }
}