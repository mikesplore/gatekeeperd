package com.gatekeeper.feature.deployment.domain.repository

import com.gatekeeper.deployment.CreateDeploymentRequest
import com.gatekeeper.deployment.UpdateDeploymentConfigurationRequest
import com.gatekeeper.docker.AdoptionDetails
import com.gatekeeper.feature.integrations.domain.repository.RegistryCredentialRecord
import java.time.LocalDateTime
import java.util.UUID
import com.gatekeeper.docker.VolumeMount
import com.gatekeeper.docker.ContainerRuntimeOptions

interface DeploymentJobRepository {
    data class AutoDeployTarget(
        val projectId: UUID, val repository: String, val gitRef: String, val registry: String,
        val imageName: String, val imageTag: String, val environment: String,
        val serviceId: UUID
    )

    data class ConfigurationSummary(
        val id: UUID,
        val repository: String?,
        val gitRef: String,
        val registry: String,
        val imageName: String,
        val imageTag: String,
        val preset: String?,
        val runtimeOptions: ContainerRuntimeOptions,
        val autoDeploy: Boolean,
        val containerPort: Int?,
        val hostPort: Int?,
        val network: String,
        val restartPolicy: String,
        val environment: String,
        val env: Map<String, String>,
        val envKeys: List<String>,
        val volumes: List<VolumeMount>,
        val secretSetId: UUID?,
        val secretSetVersion: Int?
    )

    data class EnvironmentDeploymentResult(val setId: UUID, val version: Int, val deploymentIds: List<UUID>)
    data class AdoptedRuntimeRecord(val deploymentId: UUID, val configurationId: UUID, val serviceId: UUID)

    fun findAutoDeployTargets(repository: String, gitRef: String): List<AutoDeployTarget>
    fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord?
    fun configurationIdForProject(projectId: UUID, environment: String = "production"): UUID?
    fun configurationIdForService(projectId: UUID, serviceId: UUID, environment: String = "production"): UUID?
    fun updateServiceEnvironmentAndDeploy(
        projectId: UUID, serviceId: UUID, environment: String, values: Map<String, String>, actor: String,
        registry: String? = null, replaceExisting: Boolean = false,
        sharedEnvironmentSetId: UUID? = null, sharedEnvironmentSetVersion: Int? = null
    ): EnvironmentDeploymentResult?
    fun updateSharedEnvironmentAndDeploy(
        projectId: UUID, environment: String, values: Map<String, String>, actor: String
    ): EnvironmentDeploymentResult
    fun defaultServiceIdForProject(projectId: UUID): UUID
    fun createProjectSharedEnvironmentVersion(projectId: UUID, environment: String, values: Map<String, String>, createdBy: String): Pair<UUID, Int>
    fun configurationSummary(projectId: UUID, environment: String = "production"): ConfigurationSummary?
    fun configurationSummaryForService(projectId: UUID, serviceId: UUID, environment: String = "production"): ConfigurationSummary?
    fun setPrimaryContainerPortForService(projectId: UUID, serviceId: UUID, port: Int): Boolean
    fun configurationSummaryById(id: UUID): ConfigurationSummary?
    fun upsertProjectConfiguration(projectId: UUID, request: CreateDeploymentRequest): UUID
    fun rotateProjectSecretsAndDeploy(projectId: UUID, secretEnv: Map<String, String>, actor: String): UUID?
    fun createConfiguration(projectId: UUID, request: CreateDeploymentRequest): UUID
    fun createAdoptedRuntime(projectId: UUID, serviceId: UUID, container: AdoptionDetails, containerPort: Int, actor: String): AdoptedRuntimeRecord
    fun updateConfiguration(id: UUID, request: UpdateDeploymentConfigurationRequest, replaceRepository: Boolean = false): Boolean
    fun redeployConfiguration(id: UUID): UUID?
    fun latestForService(projectId: UUID, serviceId: UUID, environment: String = "production"): DeploymentJobRecord?
    fun cancel(id: UUID): Boolean
    fun create(request: CreateDeploymentRequest): UUID
    fun createRollback(targetDeploymentId: UUID): UUID?
    fun find(id: UUID): DeploymentJobRecord?
    fun resolveEnvironmentForExecution(executionId: UUID): ResolvedDeploymentEnvironment
    fun claimNext(): DeploymentJobRecord?
    fun requeueReadyCutover(id: UUID, claimToken: UUID, reason: String): Boolean
    fun requeueInterruptedCutover(id: UUID): Boolean
    fun failInterruptedCutover(id: UUID, reason: String): Boolean
    fun staleRunningJobs(cutoff: LocalDateTime): List<StaleDeploymentJob>
    fun recoverStale(job: StaleDeploymentJob, cutoff: LocalDateTime, recoveredAt: LocalDateTime, reason: String): Boolean
    fun releaseClaim(id: UUID, claimToken: UUID, reason: String): Boolean
    fun ownsClaim(id: UUID, claimToken: UUID): Boolean
    fun isCancelled(id: UUID): Boolean
    fun update(id: UUID, claimToken: UUID, step: String? = null, log: String? = null, commitSha: String? = null, imageDigest: String? = null, status: String? = null, error: String? = null)
}
