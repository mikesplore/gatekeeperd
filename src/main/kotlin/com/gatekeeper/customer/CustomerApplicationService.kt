package com.gatekeeper.customer

import com.gatekeeper.db.repositories.ProjectQueryRepository
import com.gatekeeper.db.repositories.SupportRequestRepository
import com.gatekeeper.feature.payment.domain.usecase.GetPayment
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentsByProject
import java.util.UUID

class CustomerApplicationService(
    private val projects: ProjectQueryRepository,
    private val listPayments: ListPaymentsByProject,
    private val getPayment: GetPayment,
    private val supportRequests: SupportRequestRepository
) {
    fun project(slug: String) = projects.findBySlug(slug)
    fun payments(projectId: UUID) = listPayments(projectId)
    fun payment(id: UUID) = getPayment(id)
    fun createSupportRequest(projectId: UUID, name: String?, email: String, message: String) =
        supportRequests.create(projectId, name, email, message)
}
