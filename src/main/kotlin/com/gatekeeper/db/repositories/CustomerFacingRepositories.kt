package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.SupportRequests
import com.gatekeeper.db.tables.AccessBlockReason
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** Repository ports used by public gate and customer application services. */
interface ProjectQueryRepository {
    fun findBySlug(slug: String): ProjectRepository.ProjectRecord?
    fun findGateTargetBySlug(slug: String): GateTarget?
    fun findGateTargetByDomain(domain: String): GateTarget?
}

data class GateTarget(
    val project: ProjectRepository.ProjectRecord,
    val siteDomain: String,
    val siteSlug: String,
    val serviceName: String,
    val serviceAccessStatus: String,
    val serviceBlockReason: String?,
    val serviceBlockReasonCode: AccessBlockReason?,
    val serviceBlockReasonNote: String?
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
        return gateTarget(project, site)
    }

    override fun findGateTargetByDomain(domain: String): GateTarget? {
        val normalizedDomain = domain.trim().lowercase().substringBefore(':').trimEnd('.')
        if (normalizedDomain.isBlank()) return null
        val site = SiteRepository.findByDomain(normalizedDomain) ?: return null
        val project = ProjectRepository.findById(site.projectId) ?: return null
        return gateTarget(project, site)
    }

    private fun gateTarget(project: ProjectRepository.ProjectRecord, site: SiteRepository.SiteRecord?): GateTarget? {
        val serviceId = site?.serviceId ?: SiteRepository.findDefaultServiceId(project.id)
        val service = ServiceRepository.findAccessById(serviceId) ?: return null
        return GateTarget(
            project = project,
            siteDomain = site?.domain ?: project.domain,
            siteSlug = site?.projectSlug ?: project.slug,
            serviceName = service.name,
            serviceAccessStatus = service.accessStatus,
            serviceBlockReason = service.blockReason,
            serviceBlockReasonCode = service.blockReasonCode,
            serviceBlockReasonNote = service.blockReasonNote
        )
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
