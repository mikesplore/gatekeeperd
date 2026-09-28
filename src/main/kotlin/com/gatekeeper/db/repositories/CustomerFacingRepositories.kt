package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.SupportRequests
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Repository ports used by public gate and customer application services. */
interface ProjectQueryRepository {
    fun findBySlug(slug: String): ProjectRepository.ProjectRecord?
}

interface PaymentQueryRepository {
    fun findByProjectId(projectId: UUID): List<PaymentRepository.PaymentRecord>
    fun findById(id: UUID): PaymentRepository.PaymentRecord?
    fun findLatestPendingAuthorizationUrl(projectId: UUID): String?
}

interface SupportRequestRepository {
    fun create(projectId: UUID, name: String?, email: String, message: String): UUID
}

class ExposedProjectQueryRepository : ProjectQueryRepository {
    override fun findBySlug(slug: String) = ProjectRepository.findBySlug(slug)
}

class ExposedPaymentQueryRepository : PaymentQueryRepository {
    override fun findByProjectId(projectId: UUID) = PaymentRepository.findByProjectId(projectId)
    override fun findById(id: UUID) = PaymentRepository.findById(id)
    override fun findLatestPendingAuthorizationUrl(projectId: UUID) = PaymentRepository.findLatestPendingAuthorizationUrl(projectId)
}

class ExposedSupportRequestRepository : SupportRequestRepository {
    override fun create(projectId: UUID, name: String?, email: String, message: String): UUID = transaction {
        SupportRequests.insert {
            it[SupportRequests.projectId] = projectId
            it[SupportRequests.requesterName] = name
            it[SupportRequests.requesterEmail] = email
            it[SupportRequests.message] = message
        }[SupportRequests.id]
    }
}
