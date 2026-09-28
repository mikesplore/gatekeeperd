package com.gatekeeper.deployment

import kotlinx.serialization.Serializable
import com.gatekeeper.docker.VolumeMount

@Serializable
data class CreateDeploymentRequest(
    val repository: String? = null,
    val gitRef: String = "main",
    val registry: String = "docker.io",
    val registryCredentialId: String? = null,
    val imageName: String,
    val imageTag: String = "latest",
    val hostPort: Int? = null,
    val containerPort: Int? = null,
    val network: String = "bridge",
    val restartPolicy: String = "unless-stopped",
    val projectId: String? = null,
    val serviceId: String? = null,
    val autoDeploy: Boolean = false,
    val triggerSource: String = "manual",
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, String> = emptyMap(),
    val sharedEnvironmentSetId: String? = null,
    val sharedEnvironmentSetVersion: Int? = null,
    val volumes: List<VolumeMount> = emptyList(),
    val createNetworkIfMissing: Boolean = false,
    val environment: String = "production",
    val readinessType: String? = null,
    val readinessTarget: String? = null,
    val readinessTimeoutSeconds: Int = 60,
    val readinessIntervalSeconds: Int = 2,
    val readinessProbeTimeoutMillis: Int = 1000
)

@Serializable
data class UpdateDeploymentConfigurationRequest(
    val repository: String? = null,
    val gitRef: String? = null,
    val registry: String? = null,
    /** Null preserves the existing provider credential; an empty string clears the selection. */
    val registryCredentialId: String? = null,
    val imageName: String? = null,
    val imageTag: String? = null,
    val hostPort: Int? = null,
    val containerPort: Int? = null,
    val network: String? = null,
    val restartPolicy: String? = null,
    val env: Map<String, String>? = null,
    /** Null preserves existing secrets; an explicit map replaces them, including an empty map. */
    val secretEnv: Map<String, String>? = null,
    val sharedEnvironmentSetId: String? = null,
    val sharedEnvironmentSetVersion: Int? = null,
    val volumes: List<VolumeMount>? = null,
    val createNetworkIfMissing: Boolean? = null,
    val autoDeploy: Boolean? = null,
    val environment: String? = null,
    val readinessType: String? = null,
    val readinessTarget: String? = null,
    val readinessTimeoutSeconds: Int? = null,
    val readinessIntervalSeconds: Int? = null,
    val readinessProbeTimeoutMillis: Int? = null,
    val serviceId: String? = null
)
