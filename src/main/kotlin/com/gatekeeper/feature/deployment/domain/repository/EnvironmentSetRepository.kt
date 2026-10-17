package com.gatekeeper.feature.deployment.domain.repository

import java.time.LocalDateTime
import java.util.UUID

data class EnvironmentVersionMetadata(
    val id: UUID,
    val version: Int,
    val environment: String,
    val keys: List<String>,
    val createdAt: LocalDateTime,
    val createdBy: String?
)

data class SharedEnvironmentMetadata(val versions: List<EnvironmentVersionMetadata>) {
    val latest: EnvironmentVersionMetadata? get() = versions.maxByOrNull { it.version }
}

data class ServiceEnvironmentMetadata(val effectiveValues: Map<String, String>)
data class FingerprintedVariable(val key: String, val source: String, val fingerprint: String)

data class ActiveDeploymentInspection(
    val deploymentId: UUID,
    val status: String,
    val containerName: String?,
    val publishedPorts: Map<Int, Int>,
    val serviceId: UUID,
    val environment: String,
    val imageName: String,
    val imageTag: String,
    val imageDigest: String?,
    val commitSha: String?,
    val activeAt: LocalDateTime?,
    val sharedSetId: UUID?,
    val sharedSetVersion: Int?,
    val serviceSetId: UUID?,
    val serviceSetVersion: Int?,
    val variables: List<FingerprintedVariable>,
    val publicExposure: Boolean = false
)

interface EnvironmentSetRepository {
    fun sharedMetadata(projectId: UUID, environment: String): SharedEnvironmentMetadata
    fun serviceMetadata(projectId: UUID, serviceId: UUID, environment: String): ServiceEnvironmentMetadata?
    fun activeDeployment(serviceId: UUID, environment: String): ActiveDeploymentInspection?
    fun runtimeDeployment(serviceId: UUID, environment: String): ActiveDeploymentInspection?
}
