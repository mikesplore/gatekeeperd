package com.gatekeeper.db.repositories

import com.gatekeeper.feature.deployment.data.persistence.ExposedEnvironmentSetRepository
import com.gatekeeper.feature.deployment.domain.repository.ActiveDeploymentInspection
import com.gatekeeper.feature.deployment.domain.repository.EnvironmentSetRepository as EnvironmentSetStore
import com.gatekeeper.feature.deployment.domain.repository.ServiceEnvironmentMetadata
import com.gatekeeper.feature.deployment.domain.repository.SharedEnvironmentMetadata
import java.util.UUID

/** @deprecated Inject EnvironmentSetRepository instead. */
@Deprecated("Inject feature.deployment.domain.repository.EnvironmentSetRepository")
object EnvironmentSetRepository {
    private val delegate: EnvironmentSetStore = ExposedEnvironmentSetRepository()
    fun sharedMetadata(projectId: UUID, environment: String): SharedEnvironmentMetadata = delegate.sharedMetadata(projectId, environment)
    fun serviceMetadata(projectId: UUID, serviceId: UUID, environment: String): ServiceEnvironmentMetadata? =
        delegate.serviceMetadata(projectId, serviceId, environment)
    fun activeDeployment(serviceId: UUID, environment: String): ActiveDeploymentInspection? = delegate.activeDeployment(serviceId, environment)
    fun runtimeDeployment(serviceId: UUID, environment: String): ActiveDeploymentInspection? = delegate.runtimeDeployment(serviceId, environment)
}
