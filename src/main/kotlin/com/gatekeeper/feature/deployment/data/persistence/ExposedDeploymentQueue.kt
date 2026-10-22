package com.gatekeeper.feature.deployment.data.persistence

import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRecord
import com.gatekeeper.feature.deployment.domain.repository.ResolvedDeploymentEnvironment
import com.gatekeeper.feature.deployment.domain.repository.StaleDeploymentJob
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord
import com.gatekeeper.deployment.DeploymentQueue
import java.time.LocalDateTime
import java.util.UUID

class ExposedDeploymentQueue : DeploymentQueue {
    override fun claimNext() = DeploymentJobRepository.claimNext()
    override fun ownsClaim(id: UUID, claimToken: UUID) = DeploymentJobRepository.ownsClaim(id, claimToken)
    override fun releaseClaim(id: UUID, claimToken: UUID, reason: String) = DeploymentJobRepository.releaseClaim(id, claimToken, reason)
    override fun staleRunningJobs(cutoff: LocalDateTime): List<StaleDeploymentJob> = DeploymentJobRepository.staleRunningJobs(cutoff).map { StaleDeploymentJob(it.id, it.serviceId, it.environment, it.claimToken) }
    override fun recoverStale(job: StaleDeploymentJob, cutoff: LocalDateTime, recoveredAt: LocalDateTime, reason: String) =
        DeploymentJobRepository.recoverStale(DeploymentJobRepository.StaleJobLockKey(job.id, job.serviceId, job.environment, job.claimToken), cutoff, recoveredAt, reason)
    override fun requeueInterruptedCutover(id: UUID) = DeploymentJobRepository.requeueInterruptedCutover(id)
    override fun failInterruptedCutover(id: UUID, reason: String) = DeploymentJobRepository.failInterruptedCutover(id, reason)
    override fun requeueReadyCutover(id: UUID, claimToken: UUID, reason: String) = DeploymentJobRepository.requeueReadyCutover(id, claimToken, reason)
    override fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord? = DeploymentJobRepository.registryCredentialForDeployment(id)
    override fun resolveEnvironmentForExecution(executionId: UUID): ResolvedDeploymentEnvironment {
        val resolved = DeploymentJobRepository.resolveEnvironmentForExecution(executionId)
        return ResolvedDeploymentEnvironment(resolved.values, resolved.sourceByKey)
    }
    override fun isCancelled(id: UUID) = DeploymentJobRepository.isCancelled(id)
    override fun find(id: UUID) = DeploymentJobRepository.find(id)
    override fun update(id: UUID, claimToken: UUID, step: String?, log: String?, commitSha: String?, imageDigest: String?, status: String?, error: String?) {
        DeploymentJobRepository.update(id, claimToken, step, log, commitSha, imageDigest, status, error)
    }
    override fun cancel(id: UUID) = DeploymentJobRepository.cancel(id)
    override fun createRollback(targetDeploymentId: UUID) = DeploymentJobRepository.createRollback(targetDeploymentId)
}
