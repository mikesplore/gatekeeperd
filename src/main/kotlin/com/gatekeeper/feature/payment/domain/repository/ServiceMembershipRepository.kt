package com.gatekeeper.feature.payment.domain.repository

import java.util.UUID

interface ServiceMembershipRepository {
    fun exists(projectId: UUID, serviceId: UUID): Boolean
}