package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.SupportRequests
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Repository ports used by public gate and customer application services. */
interface ProjectQueryRepository {
    fun findBySlug(slug: String): ProjectRepository.ProjectRecord?
    fun findGateTargetBySlug(slug: String): GateTarget?
}

data class GateTarget(
    val project: ProjectRepository.ProjectRecord,
    val serviceAccessStatus: String,
    val serviceBlockReason: String?
)

interface SupportRequestRepository {
    fun create(projectId: UUID, name: String?, email: String, message: String): UUID
}

class ExposedProjectQueryRepository : ProjectQueryRepository {
    override fun findBySlug(slug: String) = ProjectRepository.findBySlug(slug)

    override fun findGateTargetBySlug(slug: String): GateTarget? {
        val site = SiteRepository.findByProjectSlug(slug)
        val project = site?.let { ProjectRepository.findById(it.projectId) }
            ?: ProjectRepository.findBySlug(slug)
            ?: return null
        val serviceId = site?.serviceId ?: SiteRepository.findDefaultServiceId(project.id)
        val service = ServiceRepository.findAccessById(serviceId) ?: return null
        return GateTarget(project, service.accessStatus, service.blockReason)
    }
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
