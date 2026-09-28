package com.gatekeeper.customer

import com.gatekeeper.db.repositories.PaymentQueryRepository
import com.gatekeeper.db.repositories.ProjectQueryRepository
import com.gatekeeper.db.repositories.SupportRequestRepository
import java.util.UUID

class CustomerApplicationService(
    private val projects: ProjectQueryRepository,
    private val payments: PaymentQueryRepository,
    private val supportRequests: SupportRequestRepository
) {
    fun project(slug: String) = projects.findBySlug(slug)
    fun payments(projectId: UUID) = payments.findByProjectId(projectId)
    fun payment(id: UUID) = payments.findById(id)
    fun createSupportRequest(projectId: UUID, name: String?, email: String, message: String) =
        supportRequests.create(projectId, name, email, message)
}
