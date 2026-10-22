package com.gatekeeper.feature.payment.data.persistence

import com.gatekeeper.db.repositories.ServiceRepository
import com.gatekeeper.feature.payment.domain.repository.ServiceMembershipRepository
import java.util.UUID

class ExposedServiceMembershipRepository : ServiceMembershipRepository {
    override fun exists(projectId: UUID, serviceId: UUID): Boolean =
        ServiceRepository.findByProjectAndId(projectId, serviceId) != null
}