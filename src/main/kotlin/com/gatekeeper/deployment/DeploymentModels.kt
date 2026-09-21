package com.gatekeeper.deployment

import kotlinx.serialization.Serializable
import com.gatekeeper.docker.VolumeMount

@Serializable
data class CreateDeploymentRequest(
    val repository: String,
    val gitRef: String = "main",
    val registry: String = "docker.io",
    val imageName: String,
    val imageTag: String = "latest",
    val containerName: String? = null,
    val hostPort: Int? = null,
    val containerPort: Int? = null,
    val network: String = "bridge",
    val restartPolicy: String = "unless-stopped"
    , val projectSlug: String? = null,
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, String> = emptyMap(),
    val volumes: List<VolumeMount> = emptyList(),
    val createNetworkIfMissing: Boolean = false
)

@Serializable
data class DeploymentJobResponse(
    val id: String,
    val repository: String,
    val gitRef: String,
    val registry: String,
    val imageName: String,
    val imageTag: String,
    val status: String,
    val currentStep: String,
    val logs: String,
    val commitSha: String? = null,
    val imageDigest: String? = null,
    val errorMessage: String? = null,
    val createdAt: String,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val updatedAt: String,
    val canRollback: Boolean = false,
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, String> = emptyMap(),
    val volumes: List<VolumeMount> = emptyList()
)
