package com.gatekeeper.admin

import com.gatekeeper.feature.deployment.domain.repository.ActiveDeploymentInspection
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentVersionMetadata
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRecord

internal fun ServiceRecord.toView() = ServiceAdminView(
    id.toString(), projectId.toString(), name, serviceType, accessStatus, isDefault, blockReason,
    blockReasonCode?.value, blockReasonNote
)

internal fun ServiceDomainRepository.Binding.toView() = ServiceDomainBindingView(
    id.toString(), serviceId.toString(), domain, containerPort, status
)

internal fun EnvironmentVersionMetadata.toView() =
    EnvironmentVersionView(id.toString(), version, environment, keys, createdAt.toString(), createdBy)

internal fun ActiveDeploymentInspection.toView() = ActiveDeploymentInspectionView(
    deploymentId.toString(), status, containerName, serviceId.toString(), environment,
    publishedPorts.mapKeys { it.key.toString() }, imageName, imageTag, imageDigest, commitSha,
    activeAt?.toString(), sharedSetId?.toString(), sharedSetVersion, serviceSetId?.toString(), serviceSetVersion,
    variables.map { FingerprintedEnvironmentVariableView(it.key, it.source, it.fingerprint) }, publicExposure
)
