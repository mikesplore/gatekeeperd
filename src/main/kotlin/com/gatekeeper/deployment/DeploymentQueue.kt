package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRecord
import com.gatekeeper.feature.deployment.domain.repository.ResolvedDeploymentEnvironment
import com.gatekeeper.feature.deployment.domain.repository.StaleDeploymentJob
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord
import java.time.LocalDateTime
import java.util.UUID

interface DeploymentQueue {
    fun claimNext(): DeploymentJobRecord?
    fun ownsClaim(id: UUID, claimToken: UUID): Boolean
    fun releaseClaim(id: UUID, claimToken: UUID, reason: String): Boolean
    fun staleRunningJobs(cutoff: LocalDateTime): List<StaleDeploymentJob>
    fun recoverStale(job: StaleDeploymentJob, cutoff: LocalDateTime, recoveredAt: LocalDateTime, reason: String): Boolean
    fun requeueInterruptedCutover(id: UUID): Boolean
    fun failInterruptedCutover(id: UUID, reason: String): Boolean
    fun requeueReadyCutover(id: UUID, claimToken: UUID, reason: String): Boolean
    fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord?
    fun resolveEnvironmentForExecution(executionId: UUID): ResolvedDeploymentEnvironment
    fun isCancelled(id: UUID): Boolean
    fun find(id: UUID): DeploymentJobRecord?
    fun update(id: UUID, claimToken: UUID, step: String? = null, log: String? = null, commitSha: String? = null, imageDigest: String? = null, status: String? = null, error: String? = null)
    fun cancel(id: UUID): Boolean
    fun createRollback(targetDeploymentId: UUID): UUID?
}
