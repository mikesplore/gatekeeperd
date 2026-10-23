package com.gatekeeper.feature.deployment.data.persistence

import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRecord
import com.gatekeeper.feature.deployment.domain.repository.ResolvedDeploymentEnvironment
import com.gatekeeper.feature.deployment.domain.repository.StaleDeploymentJob
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord
import com.gatekeeper.deployment.DeploymentQueue
import java.time.LocalDateTime
import java.util.UUID

class ExposedDeploymentQueue(private val repository: DeploymentJobRepository) : DeploymentQueue {
    override fun claimNext() = repository.claimNext()
    override fun ownsClaim(id: UUID, claimToken: UUID) = repository.ownsClaim(id, claimToken)
    override fun releaseClaim(id: UUID, claimToken: UUID, reason: String) = repository.releaseClaim(id, claimToken, reason)
    override fun staleRunningJobs(cutoff: LocalDateTime): List<StaleDeploymentJob> = repository.staleRunningJobs(cutoff)
    override fun recoverStale(job: StaleDeploymentJob, cutoff: LocalDateTime, recoveredAt: LocalDateTime, reason: String) =
        repository.recoverStale(job, cutoff, recoveredAt, reason)
    override fun requeueInterruptedCutover(id: UUID) = repository.requeueInterruptedCutover(id)
    override fun failInterruptedCutover(id: UUID, reason: String) = repository.failInterruptedCutover(id, reason)
    override fun requeueReadyCutover(id: UUID, claimToken: UUID, reason: String) = repository.requeueReadyCutover(id, claimToken, reason)
    override fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord? = repository.registryCredentialForDeployment(id)
    override fun resolveEnvironmentForExecution(executionId: UUID): ResolvedDeploymentEnvironment = repository.resolveEnvironmentForExecution(executionId)
    override fun isCancelled(id: UUID) = repository.isCancelled(id)
    override fun find(id: UUID) = repository.find(id)
    override fun update(id: UUID, claimToken: UUID, step: String?, log: String?, commitSha: String?, imageDigest: String?, status: String?, error: String?) {
        repository.update(id, claimToken, step, log, commitSha, imageDigest, status, error)
    }
    override fun cancel(id: UUID) = repository.cancel(id)
    override fun createRollback(targetDeploymentId: UUID) = repository.createRollback(targetDeploymentId)
}
