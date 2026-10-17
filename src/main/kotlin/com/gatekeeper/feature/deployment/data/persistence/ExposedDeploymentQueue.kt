package com.gatekeeper.feature.deployment.data.persistence

import com.gatekeeper.db.repositories.DeploymentJobRecord
import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.RegistryCredentialRecord
import com.gatekeeper.deployment.DeploymentQueue
import java.time.LocalDateTime
import java.util.UUID

class ExposedDeploymentQueue : DeploymentQueue {
    override fun claimNext() = DeploymentJobRepository.claimNext()
    override fun ownsClaim(id: UUID, claimToken: UUID) = DeploymentJobRepository.ownsClaim(id, claimToken)
    override fun releaseClaim(id: UUID, claimToken: UUID, reason: String) = DeploymentJobRepository.releaseClaim(id, claimToken, reason)
    override fun staleRunningJobs(cutoff: LocalDateTime) = DeploymentJobRepository.staleRunningJobs(cutoff)
    override fun recoverStale(job: DeploymentJobRepository.StaleJobLockKey, cutoff: LocalDateTime, recoveredAt: LocalDateTime, reason: String) =
        DeploymentJobRepository.recoverStale(job, cutoff, recoveredAt, reason)
    override fun requeueInterruptedCutover(id: UUID) = DeploymentJobRepository.requeueInterruptedCutover(id)
    override fun failInterruptedCutover(id: UUID, reason: String) = DeploymentJobRepository.failInterruptedCutover(id, reason)
    override fun requeueReadyCutover(id: UUID, claimToken: UUID, reason: String) = DeploymentJobRepository.requeueReadyCutover(id, claimToken, reason)
    override fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord? = DeploymentJobRepository.registryCredentialForDeployment(id)
    override fun resolveEnvironmentForExecution(executionId: UUID): DeploymentJobRepository.ResolvedEnvironment = DeploymentJobRepository.resolveEnvironmentForExecution(executionId)
    override fun isCancelled(id: UUID) = DeploymentJobRepository.isCancelled(id)
    override fun find(id: UUID) = DeploymentJobRepository.find(id)
    override fun update(id: UUID, claimToken: UUID, step: String?, log: String?, commitSha: String?, imageDigest: String?, status: String?, error: String?) {
        DeploymentJobRepository.update(id, claimToken, step, log, commitSha, imageDigest, status, error)
    }
    override fun cancel(id: UUID) = DeploymentJobRepository.cancel(id)
    override fun createRollback(targetDeploymentId: UUID) = DeploymentJobRepository.createRollback(targetDeploymentId)
}
