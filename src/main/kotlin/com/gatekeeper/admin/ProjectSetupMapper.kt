package com.gatekeeper.admin

import com.gatekeeper.feature.deployment.domain.repository.DeploymentJobRepository

fun DeploymentJobRepository.ConfigurationSummary.toSetupResponse() = ProjectSetupConfigurationResponse(
    id.toString(), repository, gitRef, registry, imageName, imageTag, autoDeploy, containerPort, hostPort, network, restartPolicy,
    environment, env, envKeys, secretSetId?.toString(), secretSetVersion
)
