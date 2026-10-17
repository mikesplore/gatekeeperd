package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRecord
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.RegistryCredentialRecord
import java.time.LocalDateTime
import java.util.UUID

interface DeploymentQueue {
    fun claimNext(): DeploymentJobRecord?
    fun ownsClaim(id: UUID, claimToken: UUID): Boolean
    fun releaseClaim(id: UUID, claimToken: UUID, reason: String): Boolean
    fun staleRunningJobs(cutoff: LocalDateTime): List<DeploymentJobRepository.StaleJobLockKey>
    fun recoverStale(job: DeploymentJobRepository.StaleJobLockKey, cutoff: LocalDateTime, recoveredAt: LocalDateTime, reason: String): Boolean
    fun requeueInterruptedCutover(id: UUID): Boolean
    fun failInterruptedCutover(id: UUID, reason: String): Boolean
    fun requeueReadyCutover(id: UUID, claimToken: UUID, reason: String): Boolean
    fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord?
    fun resolveEnvironmentForExecution(executionId: UUID): DeploymentJobRepository.ResolvedEnvironment
    fun isCancelled(id: UUID): Boolean
    fun find(id: UUID): DeploymentJobRecord?
    fun update(id: UUID, claimToken: UUID, step: String? = null, log: String? = null, commitSha: String? = null, imageDigest: String? = null, status: String? = null, error: String? = null)
    fun cancel(id: UUID): Boolean
    fun createRollback(targetDeploymentId: UUID): UUID?
}
