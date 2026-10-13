package com.gatekeeper.deployment

import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeploymentJobRecoveryTest {
    private val now = LocalDateTime.parse("2026-01-01T12:00:00")
    private val job = RecoverableDeploymentJob(UUID.randomUUID(), UUID.randomUUID(), "production", UUID.randomUUID())

    @Test
    fun `lock acquisition failure releases the claimed job for recovery`() {
        val state = FakeJobState("running")
        assertFailsWith<IllegalStateException> {
            DeploymentJobRecovery.executeClaimed(
                job,
                withLock = { _, _ -> error("database lock unavailable") },
                ownsClaim = { true },
                process = { Unit },
                release = { state.release(it.message.orEmpty()) }
            )
        }
        assertEquals("queued", state.status)
        assertTrue(state.diagnostic.contains("database lock unavailable"))
    }

    @Test
    fun `repository failure after claim is released when possible and remains recoverable otherwise`() {
        val state = FakeJobState("running")
        val failure = assertFailsWith<IllegalStateException> {
            DeploymentJobRecovery.executeClaimed(
                job,
                withLock = { _, block -> block() },
                ownsClaim = { error("repository read failed") },
                process = { Unit },
                release = { state.release(it.message.orEmpty()) }
            )
        }
        assertEquals("queued", state.status)
        assertEquals("repository read failed", state.diagnostic)
        assertEquals("repository read failed", failure.message)

        val dbUnavailable = IllegalStateException("database unavailable")
        assertFailsWith<IllegalStateException> {
            DeploymentJobRecovery.executeClaimed(
                job,
                withLock = { _, _ -> throw dbUnavailable },
                ownsClaim = { true },
                process = { Unit },
                release = { throw IllegalStateException("release failed") }
            )
        }
        assertEquals(1, dbUnavailable.suppressed.size)
        // The still-running row remains eligible for periodic stale recovery.
        val stranded = FakeJobState("running")
        assertEquals(1, recover(stranded))
    }

    @Test
    fun `recovery skips an actively locked deployment`() {
        val state = FakeJobState("running")
        val recovered = DeploymentJobRecovery.recoverStale(
            listOf(job), now.minusMinutes(30), now,
            tryWithLock = { _, _ -> null },
            recover = { _, _, _ -> state.recover() }
        )
        assertEquals(0, recovered)
        assertEquals("running", state.status)
    }

    @Test
    fun `periodic recovery requeues abandoned job without process restart`() {
        val state = FakeJobState("running")
        assertEquals(1, recover(state))
        assertEquals("queued", state.status)
        assertTrue(state.diagnostic.contains("Recovered"))
    }

    @Test
    fun `two workers recovering the same job only transition it once`() {
        val state = FakeJobState("running")
        val lockTaken = AtomicBoolean(false)
        val firstWorkerLocked = CountDownLatch(1)
        val allowFirstWorkerToFinish = CountDownLatch(1)
        val tryLock: (String, () -> Boolean) -> Boolean? = { _, block ->
            if (!lockTaken.compareAndSet(false, true)) null
            else try {
                firstWorkerLocked.countDown()
                allowFirstWorkerToFinish.await()
                block()
            } finally { lockTaken.set(false) }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> {
                DeploymentJobRecovery.recoverStale(listOf(job), now.minusMinutes(30), now, tryLock) { _, _, _ -> state.recover() }
            }
            assertTrue(firstWorkerLocked.await(5, TimeUnit.SECONDS))
            val second = executor.submit<Int> {
                DeploymentJobRecovery.recoverStale(listOf(job), now.minusMinutes(30), now, tryLock) { _, _, _ -> state.recover() }
            }
            assertEquals(0, second.get())
            allowFirstWorkerToFinish.countDown()
            assertEquals(1, first.get())
        } finally {
            allowFirstWorkerToFinish.countDown()
            executor.shutdownNow()
        }
        assertEquals("queued", state.status)
    }

    @Test
    fun `cancellation stays terminal and interrupted attempt can be retried`() {
        val cancelled = FakeJobState("cancelled")
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            DeploymentJobRecovery.executeClaimed(
                job,
                withLock = { _, block -> block() },
                ownsClaim = { true },
                process = { throw kotlinx.coroutines.CancellationException("cancelled") },
                release = { cancelled.release(it.message.orEmpty()) }
            )
        }
        assertEquals("cancelled", cancelled.status)

        val retry = FakeJobState("running")
        assertFailsWith<IllegalStateException> {
            DeploymentJobRecovery.executeClaimed(
                job,
                withLock = { _, block -> block() },
                ownsClaim = { true },
                process = { error("temporary failure") },
                release = { retry.release(it.message.orEmpty()) }
            )
        }
        assertEquals("queued", retry.status)
        var attempts = 0
        DeploymentJobRecovery.executeClaimed(
            job,
            withLock = { _, block -> block() },
            ownsClaim = { true },
            process = { attempts++; Unit },
            release = { error("unexpected release") }
        )
        assertEquals(1, attempts)
    }

    @Test
    fun `failed recovery leaves job eligible for another pass`() {
        val state = FakeJobState("running")
        assertFailsWith<IllegalStateException> {
            DeploymentJobRecovery.recoverStale(listOf(job), now.minusMinutes(30), now, { _, block -> block() }) { _, _, _ ->
                error("recovery transaction failed")
            }
        }
        assertEquals("running", state.status)
        assertEquals(1, recover(state))
        assertEquals("queued", state.status)
    }

    private fun recover(state: FakeJobState) = DeploymentJobRecovery.recoverStale(
        listOf(job), now.minusMinutes(30), now,
        tryWithLock = { _, block -> block() },
        recover = { _, cutoff, recoveredAt ->
            assertEquals(now.minusMinutes(30), cutoff)
            assertEquals(now, recoveredAt)
            state.recover()
        }
    )

    private class FakeJobState(var status: String) {
        var diagnostic: String = ""
        fun release(reason: String): Boolean {
            if (status != "running") return false
            status = "queued"
            diagnostic = reason
            return true
        }

        fun recover(): Boolean {
            if (status != "running") return false
            status = "queued"
            diagnostic = "Recovered abandoned job"
            return true
        }
    }
}
