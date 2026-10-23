package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.feature.payment.domain.repository.ServiceMembershipRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import java.util.UUID

class ExposedServiceMembershipRepository(private val serviceRepository: ServiceRepository) : ServiceMembershipRepository {
    override fun exists(projectId: UUID, serviceId: UUID): Boolean =
        serviceRepository.findByProjectAndId(projectId, serviceId) != null
}