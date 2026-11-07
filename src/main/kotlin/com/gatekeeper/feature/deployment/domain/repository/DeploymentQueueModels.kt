package com.gatekeeper.feature.deployment.domain.repository

import com.gatekeeper.docker.VolumeMount
import com.gatekeeper.docker.ContainerRuntimeOptions
import java.time.LocalDateTime
import java.util.UUID

data class DeploymentJobRecord(
    val id: UUID, val repository: String?, val gitRef: String, val registry: String,
    val imageName: String, val imageTag: String, val hostPort: Int?, val containerPort: Int?, val network: String, val restartPolicy: String,
    val preset: String? = null,
    val runtimeOptions: ContainerRuntimeOptions = ContainerRuntimeOptions(),
    val env: Map<String, String>, val secretEnv: Map<String, String>, val volumes: List<VolumeMount>, val createNetworkIfMissing: Boolean,
    val status: String, val currentStep: String, val logs: String, val commitSha: String?, val imageDigest: String?, val errorMessage: String?,
    val createdAt: LocalDateTime, val startedAt: LocalDateTime?, val completedAt: LocalDateTime?, val updatedAt: LocalDateTime,
    val projectId: UUID, val triggerSource: String, val environment: String = "production",
    val readinessType: String? = null, val readinessTarget: String? = null, val readinessTimeoutSeconds: Int = 60,
    val readinessIntervalSeconds: Int = 2, val readinessProbeTimeoutMillis: Int = 1000,
    val secretSetId: UUID? = null, val secretSetVersion: Int? = null, val serviceId: UUID,
    val serviceName: String = "default", val claimToken: UUID? = null
)

data class ResolvedDeploymentEnvironment(val values: Map<String, String>, val sourceByKey: Map<String, String>)

data class StaleDeploymentJob(val id: UUID, val serviceId: UUID, val environment: String, val claimToken: UUID?)
