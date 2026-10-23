package com.gatekeeper.feature.customer.domain.repository

import java.util.UUID

interface SupportRequestRepository {
    fun create(projectId: UUID, name: String?, email: String, message: String): UUID
}