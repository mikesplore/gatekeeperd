package com.gatekeeper.deployment

import java.time.LocalDateTime
import java.util.UUID

internal data class RecoverableDeploymentJob(
    val id: UUID,
    val serviceId: UUID,
    val environment: String,
    val claimToken: UUID?
)

/** Small coordination seam for deterministic worker claim and stale-recovery tests. */
internal object DeploymentJobRecovery {
    fun <T> executeClaimed(
        job: RecoverableDeploymentJob,
        withLock: (String, () -> T?) -> T?,
        ownsClaim: () -> Boolean,
        process: () -> T,
        release: (Throwable) -> Unit
    ): T? {
        try {
            return withLock(lockKey(job)) {
                if (ownsClaim()) process() else null
            }
        } catch (error: Throwable) {
            runCatching { release(error) }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun recoverStale(
        jobs: List<RecoverableDeploymentJob>,
        cutoff: LocalDateTime,
        recoveredAt: LocalDateTime,
        tryWithLock: (String, () -> Boolean) -> Boolean?,
        recover: (RecoverableDeploymentJob, LocalDateTime, LocalDateTime) -> Boolean
    ): Int = jobs.sumOf { job ->
        tryWithLock(lockKey(job)) { recover(job, cutoff, recoveredAt) }?.let { if (it) 1 else 0 } ?: 0
    }

    private fun lockKey(job: RecoverableDeploymentJob) = "deployment-service:${job.serviceId}:${job.environment}"
}
